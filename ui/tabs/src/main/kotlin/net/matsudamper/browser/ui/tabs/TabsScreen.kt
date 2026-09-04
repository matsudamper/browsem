package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.ui.common.ThemeSurfaceStatusBarAppearanceEffect
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId

internal object TabsLayoutDefaults {
    val minCellWidth: Dp = 220.dp
    val gridPadding: Dp = 12.dp
    val gridSpacing: Dp = 12.dp
    const val cardAspectRatio: Float = 1f

    fun calculateColumns(availableWidth: Dp): Int {
        return (availableWidth / minCellWidth).toInt().coerceAtLeast(2)
    }

    fun calculateCardWidth(availableWidth: Dp, columns: Int): Dp {
        val spacingWidth = gridSpacing * (columns - 1)
        val contentWidth = availableWidth - (gridPadding * 2) - spacingWidth
        return contentWidth / columns
    }
}

/** PagerIndicator 計算用の軽量アイテム情報。LazyListItemInfo を Compose に依存しない形で保持する */
internal data class IndicatorItemInfo(val index: Int, val offset: Int, val size: Int)

/**
 * インジゲータの描画範囲 (startX, width) を計算する。
 * @param items 可視タブの位置情報リスト
 * @param currentPage 現在のページインデックス
 * @param offsetFraction ページのスクロールオフセット割合（-1.0〜1.0）
 * @param startOffsetPx LazyRow の左端から原点までのオフセット（px）
 * @return (startX, width) のペア。計算不能なら null
 */
internal fun calculatePagerIndicatorBounds(
    items: List<IndicatorItemInfo>,
    currentPage: Int,
    offsetFraction: Float,
    startOffsetPx: Float,
): Pair<Float, Float>? {
    val currentItem = items.firstOrNull { it.index == currentPage } ?: return null
    val nextPage = if (offsetFraction >= 0f) currentPage + 1 else currentPage - 1
    val nextItem = items.firstOrNull { it.index == nextPage }
    val fraction = kotlin.math.abs(offsetFraction)
    val rawStartX = startOffsetPx + currentItem.offset.toFloat()
    return if (nextItem != null && fraction > 0f) {
        val rawNextX = startOffsetPx + nextItem.offset.toFloat()
        val startX = rawStartX + (rawNextX - rawStartX) * fraction
        val width = currentItem.size.toFloat() + (nextItem.size - currentItem.size).toFloat() * fraction
        Pair(startX, width)
    } else {
        Pair(rawStartX, currentItem.size.toFloat())
    }
}

@Composable
fun TabsScreen(
    uiState: TabsScreenUiState,
    modifier: Modifier = Modifier,
) {
    ThemeSurfaceStatusBarAppearanceEffect()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentCallbacks by rememberUpdatedState(uiState.callbacks)
    val pendingClosedTab = uiState.pendingClosedTab

    LaunchedEffect(pendingClosedTab) {
        if (pendingClosedTab != null) {
            val result = snackbarHostState.showSnackbar(
                message = pendingClosedTab.title,
                actionLabel = "戻す",
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> currentCallbacks.onUndoCloseTab()
                SnackbarResult.Dismissed -> currentCallbacks.onConfirmCloseTab()
            }
        }
    }

    // 画面から離れるときに保留中のタブ削除を確定する
    DisposableEffect(Unit) {
        onDispose {
            currentCallbacks.onConfirmCloseTab()
        }
    }

    when (val loadingState = uiState.loadingState) {
        is TabsScreenUiState.LoadingState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is TabsScreenUiState.LoadingState.Loaded -> {
            TabsScreenLoadedContent(
                groupedTabs = loadingState.groupedTabs,
                groups = loadingState.groups,
                activeGroupIndex = loadingState.activeGroupIndex,
                selectedTabId = loadingState.selectedTabId,
                groupHasPlayingTab = loadingState.groupHasPlayingTab,
                snackbarHostState = snackbarHostState,
                newTabListener = loadingState.newTabListener,
                onReorderTabs = currentCallbacks::onReorderTabs,
                onReorderGroups = currentCallbacks::onReorderGroups,
                onGroupSelected = currentCallbacks::onGroupSelected,
                onGroupPageChanged = currentCallbacks::onGroupPageChanged,
                onAddGroup = currentCallbacks::onAddGroup,
                onRenameGroup = currentCallbacks::onRenameGroup,
                onDeleteGroup = currentCallbacks::onDeleteGroup,
                onToggleDefaultGroup = currentCallbacks::onToggleDefaultGroup,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TabsScreenLoadedContent(
    groupedTabs: List<List<TabsScreenTabData>>,
    groups: List<TabGroupData>,
    activeGroupIndex: Int,
    selectedTabId: String?,
    groupHasPlayingTab: List<Boolean>,
    snackbarHostState: SnackbarHostState,
    newTabListener: TabsScreenUiState.LoadingState.Loaded.NewTabListener,
    onReorderTabs: (groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) -> Unit,
    onReorderGroups: (fromIndex: Int, toIndex: Int) -> Unit,
    onGroupSelected: (Int) -> Unit,
    onGroupPageChanged: (Int) -> Unit,
    onAddGroup: () -> Unit,
    onRenameGroup: (groupIndex: Int, newName: String) -> Unit,
    onDeleteGroup: (groupIndex: Int) -> Unit,
    onToggleDefaultGroup: (groupIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safePageCount = groups.size.coerceAtLeast(1)
    val safeInitialPage = activeGroupIndex.coerceIn(0, safePageCount - 1)
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { groups.size.coerceAtLeast(1) },
    )

    // グループタブバーの LazyRow 状態（PagerIndicator と共有してスクロール同期に使う）
    val groupTabListState = rememberLazyListState()
    val density = LocalDensity.current
    val currentOnGroupPageChanged by rememberUpdatedState(onGroupPageChanged)

    // 直前にアクティブだったグループの ID。
    // 並び替えでアクティブグループの index だけが変わった場合を判別するために保持する
    var lastActiveGroupId by remember { mutableStateOf(groups.getOrNull(safeInitialPage)?.id) }

    // グループタブの長押しドラッグ中はタブバーが自動スクロールするため、同期処理を止める
    var isGroupDragging by remember { mutableStateOf(false) }

    // ViewModelのactiveGroupIndex変化 → ページスクロールとタブバースクロールを同期
    // index が変わらずアクティブグループだけが入れ替わる場合（削除など）にも
    // lastActiveGroupId を更新する必要があるため、ID もキーに含める。
    // isGroupDragging もキーに含めないと、ドラッグ中に activeGroupIndex が変わった場合に
    // タブバーの同期がスキップされたまま再開されず、アクティブグループが画面外に残る
    val activeGroupId = groups.getOrNull(activeGroupIndex)?.id
    LaunchedEffect(activeGroupIndex, activeGroupId, isGroupDragging) {
        // 同じグループのまま index だけが変わった = 長押しドラッグによる並び替え。
        // この場合ページ内容も同時に入れ替わっているためアニメーションさせると
        // 別グループのページが流れて見えるので、即座に位置だけ合わせる
        val isReorder = activeGroupId != null && activeGroupId == lastActiveGroupId
        lastActiveGroupId = activeGroupId
        if (pagerState.currentPage != activeGroupIndex && activeGroupIndex in 0 until groups.size) {
            if (isReorder) {
                pagerState.scrollToPage(activeGroupIndex)
            } else {
                pagerState.animateScrollToPage(activeGroupIndex)
            }
        }
        if (!isGroupDragging && activeGroupIndex in 0 until groups.size) {
            val layoutInfo = groupTabListState.layoutInfo
            val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeGroupIndex }
            if (targetItem == null) {
                // 画面外にある場合は通常スクロール（左端揃え）
                groupTabListState.animateScrollToItem(activeGroupIndex)
            } else {
                val itemViewportLeft = (targetItem.offset - layoutInfo.viewportStartOffset).toFloat()
                val itemViewportRight = itemViewportLeft + targetItem.size
                val viewportWidth = layoutInfo.viewportSize.width.toFloat()
                // スクロール量に ±24dp のバッファを加えて少し余裕を持たせる
                val bufferPx = with(density) { 24.dp.toPx() }
                when {
                    itemViewportRight > viewportWidth -> {
                        // 右にはみ出している: はみ出し分 + バッファ分スクロール
                        groupTabListState.animateScrollBy(itemViewportRight - viewportWidth + bufferPx)
                    }

                    itemViewportLeft < 0f -> {
                        // 左にはみ出している: バッファ分手前でとめる（負 = 左方向）
                        groupTabListState.animateScrollBy(itemViewportLeft - bufferPx)
                    }
                    // else: 完全に表示されているのでスクロール不要
                }
            }
        }
    }

    // ユーザーのスワイプ → ViewModelへ通知（settledPage でアニメーション完了後のみ通知）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentOnGroupPageChanged(page)
        }
    }

    // グループタブバー上の各グループタブのルート座標 bounds を保持する
    val groupTabBounds = remember { mutableMapOf<Int, Rect>() }
    // グループが削除・並び替えされた際に無効なインデックスのエントリを除去する
    LaunchedEffect(groups) {
        val validIndices = groups.indices.toSet()
        groupTabBounds.keys.retainAll(validIndices)
    }

    // タブドラッグ中のルート座標中心を追跡（各ページの DragDropState から更新される）
    var tabDragCenterInRoot by remember { mutableStateOf(Offset.Zero) }
    var isTabDragging by remember { mutableStateOf(false) }

    // ドラッグ中に中心がどのグループタブ上にあるかを判定する
    val highlightedGroupIndex = if (isTabDragging) {
        groupTabBounds.entries.firstOrNull { (_, bounds) ->
            bounds.contains(tabDragCenterInRoot)
        }?.key
    } else {
        null
    }

    // グループ移動ダイアログの状態：長押しして移動せずに離したタブのID
    var moveDialogOnGroupSelected by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    // 名前変更ダイアログの対象グループインデックス
    var renameDialogGroupIndex by remember { mutableStateOf<Int?>(null) }

    // 削除確認ダイアログの対象グループインデックス
    var deleteDialogGroupIndex by remember { mutableStateOf<Int?>(null) }

    var floatingActionButtonBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { snackbarData ->
                // スワイプで Snackbar を dismiss できるようにする
                key(snackbarData) {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) {
                                snackbarData.dismiss()
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {},
                    ) {
                        SnackbarContent(snackbarData = snackbarData)
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = newTabListener::onOpenNewTab,
                modifier = Modifier
                    .testTag(TabsScreenTestTags.AddTabButton.testTag)
                    .onGloballyPositioned { coordinates ->
                        floatingActionButtonBoundsInRoot = coordinates.boundsInRoot()
                    },
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "新規タブ")
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // グループタブバー（上辺角丸タブ）
            GroupTabBar(
                groups = groups,
                activeGroupIndex = activeGroupIndex,
                pagerState = pagerState,
                highlightedDropTargetIndex = highlightedGroupIndex,
                groupHasPlayingTab = groupHasPlayingTab,
                onGroupSelected = onGroupSelected,
                onReorderGroups = onReorderGroups,
                onAddGroup = onAddGroup,
                onGroupTabBoundsChanged = { index, bounds ->
                    groupTabBounds[index] = bounds
                },
                onDraggingChanged = { isGroupDragging = it },
                listState = groupTabListState,
                modifier = Modifier.fillMaxWidth(),
            )

            // スワイプ進捗インジケータ
            PagerIndicator(
                pagerState = pagerState,
                listState = groupTabListState,
                modifier = Modifier.fillMaxWidth(),
            )

            // グループごとのタブグリッド（HorizontalPager）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = !isTabDragging,
            ) { page ->
                val tabsForPage = groupedTabs.getOrElse(page) { emptyList() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TabsScreenTestTags.Page(page).testTag),
                ) {
                    TabGroupMenu(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        page = page,
                        groups = groups,
                        onClickDelete = {
                            deleteDialogGroupIndex = page
                        },
                        onClickRename = {
                            renameDialogGroupIndex = page
                        },
                        onToggleDefaultGroup = {
                            onToggleDefaultGroup(page)
                        },
                    )
                    GroupTabGrid(
                        tabs = tabsForPage,
                        selectedTabId = selectedTabId,
                        onReorderTabs = { from, to -> onReorderTabs(page, from, to) },
                        onTabDragStateChanged = { dragging, centerInRoot ->
                            isTabDragging = dragging
                            tabDragCenterInRoot = centerInRoot
                        },
                        onTabDropped = { tab ->
                            val targetIndex = groupTabBounds.entries.firstOrNull { (_, bounds) ->
                                bounds.contains(tabDragCenterInRoot)
                            }?.key
                            if (targetIndex != null && targetIndex != page) {
                                tab.listener.onMoveToGroup(targetIndex)
                            }
                        },
                        onTabLongPressWithoutDrag = { tab ->
                            moveDialogOnGroupSelected = tab.listener::onMoveToGroup
                        },
                        floatingActionButtonBoundsInRoot = floatingActionButtonBoundsInRoot,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // グループ移動ダイアログ
    val moveDialogHandler = moveDialogOnGroupSelected
    if (moveDialogHandler != null) {
        MoveTabToGroupDialog(
            groups = groups,
            currentGroupIndex = activeGroupIndex,
            onGroupSelected = { targetGroupIndex ->
                moveDialogHandler(targetGroupIndex)
                moveDialogOnGroupSelected = null
            },
            onDismiss = { moveDialogOnGroupSelected = null },
        )
    }

    // 名前変更ダイアログ
    val renameIndex = renameDialogGroupIndex
    if (renameIndex != null) {
        val group = groups.getOrNull(renameIndex)
        if (group != null) {
            RenameGroupDialog(
                currentName = group.name,
                onConfirm = { newName ->
                    onRenameGroup(renameIndex, newName)
                    renameDialogGroupIndex = null
                },
                onDismiss = { renameDialogGroupIndex = null },
            )
        }
    }

    // 削除確認ダイアログ
    val deleteIndex = deleteDialogGroupIndex
    if (deleteIndex != null) {
        val group = groups.getOrNull(deleteIndex)
        if (group != null) {
            DeleteGroupDialog(
                groupName = group.name,
                onConfirm = {
                    onDeleteGroup(deleteIndex)
                    deleteDialogGroupIndex = null
                },
                onDismiss = { deleteDialogGroupIndex = null },
            )
        }
    }
}

@Composable
private fun TabGroupMenu(
    page: Int,
    groups: List<TabGroupData>,
    onToggleDefaultGroup: (page: Int) -> Unit,
    onClickRename: () -> Unit,
    onClickDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "デフォルト",
                style = MaterialTheme.typography.labelMedium,
            )
            // 外部アプリ（Intent）経由でURLを開いた際に割り当てるグループを指定する。
            // タブ一覧での新規追加・target=_blank など、アプリ内操作には適用されない。
            Switch(
                modifier = Modifier.testTag(TabsScreenTestTags.DefaultGroupSwitch(page).testTag),
                checked = groups.getOrNull(page)?.isDefault ?: false,
                onCheckedChange = { onToggleDefaultGroup(page) },
            )
        }
        // グループの名前変更・削除を格納する3点メニュー
        var groupMenuExpanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { groupMenuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "メニュー",
                )
            }
            DropdownMenu(
                expanded = groupMenuExpanded,
                onDismissRequest = { groupMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("名前変更") },
                    onClick = {
                        groupMenuExpanded = false
                        onClickRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("削除") },
                    enabled = groups.size > 1,
                    onClick = {
                        groupMenuExpanded = false
                        onClickDelete()
                    },
                )
            }
        }
    }
}

/** タブを閉じたときに表示する Snackbar の本体 */
@Composable
private fun SnackbarContent(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    Snackbar(
        modifier = modifier,
        action = {
            snackbarData.visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { snackbarData.performAction() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SnackbarDefaults.actionContentColor,
                    ),
                ) {
                    Text(label)
                }
            }
        },
    ) {
        Text(
            text = snackbarData.visuals.message,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * HorizontalPager のスクロール進捗に連動して動くインジケータ。
 * グループタブバーの直下に表示し、LazyRow の実際のアイテム位置に合わせてスライドするバーを描画する。
 * タブバーがスクロールされていても表示位置と同期する。
 */
@Composable
private fun PagerIndicator(
    pagerState: PagerState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val indicatorColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // スクロールやページ変化時に再コンポーズされるよう composable body で状態を読み取る
    val layoutInfo = listState.layoutInfo
    val currentPage = pagerState.currentPage
    val offsetFraction = pagerState.currentPageOffsetFraction
    // viewportStartOffset = -contentPadding.start のため符号反転で描画座標へのオフセット量を得る
    val startOffsetPx = -layoutInfo.viewportStartOffset.toFloat()
    val items = layoutInfo.visibleItemsInfo.map { IndicatorItemInfo(it.index, it.offset, it.size) }
    val bounds = calculatePagerIndicatorBounds(items, currentPage, offsetFraction, startOffsetPx)

    Canvas(modifier = modifier.height(2.dp)) {
        drawRect(color = trackColor)
        if (bounds != null) {
            val (startX, width) = bounds
            drawRect(
                color = indicatorColor,
                topLeft = Offset(x = startX, y = 0f),
                size = Size(width = width, height = size.height),
            )
        }
    }
}

@Composable
@Preview
private fun Preview() {
    val groups = remember {
        listOf(
            TabGroupData(TabGroupId("g1"), "デフォルト"),
            TabGroupData(TabGroupId("g2"), "開発"),
        )
    }
    val groupedTabs = remember {
        listOf(
            listOf(
                previewTabData(id = "1", title = "Example Domain"),
                previewTabData(id = "2", title = "Google"),
            ),
            listOf(
                previewTabData(id = "3", title = "GitHub"),
            ),
        )
    }
    TabsScreenLoadedContent(
        groupedTabs = groupedTabs,
        groups = groups,
        activeGroupIndex = 0,
        selectedTabId = "1",
        groupHasPlayingTab = emptyList(),
        snackbarHostState = remember { SnackbarHostState() },
        newTabListener = PreviewNewTabListener,
        onReorderTabs = { _, _, _ -> },
        onReorderGroups = { _, _ -> },
        onGroupSelected = {},
        onGroupPageChanged = {},
        onAddGroup = {},
        onRenameGroup = { _, _ -> },
        onDeleteGroup = {},
        onToggleDefaultGroup = {},
    )
}

/** グループが1つのみの場合 (削除メニューが disabled になる状態) */
@Composable
@Preview
private fun PreviewSingleGroup() {
    val groups = remember {
        listOf(
            TabGroupData(TabGroupId("g1"), "デフォルト"),
        )
    }
    val groupedTabs = remember {
        listOf(
            listOf(
                previewTabData(id = "1", title = "Example Domain"),
                previewTabData(id = "2", title = "Google"),
            ),
        )
    }
    TabsScreenLoadedContent(
        groupedTabs = groupedTabs,
        groups = groups,
        activeGroupIndex = 0,
        selectedTabId = "1",
        groupHasPlayingTab = emptyList(),
        snackbarHostState = remember { SnackbarHostState() },
        newTabListener = PreviewNewTabListener,
        onReorderTabs = { _, _, _ -> },
        onReorderGroups = { _, _ -> },
        onGroupSelected = {},
        onGroupPageChanged = {},
        onAddGroup = {},
        onRenameGroup = { _, _ -> },
        onDeleteGroup = {},
        onToggleDefaultGroup = {},
    )
}

/** Snackbar が表示されている状態の Preview */
@Composable
@Preview
private fun PreviewWithSnackbar() {
    val groups = remember {
        listOf(
            TabGroupData(TabGroupId("g1"), "デフォルト"),
            TabGroupData(TabGroupId("g2"), "開発"),
        )
    }
    val groupedTabs = remember {
        listOf(
            listOf(
                previewTabData(id = "1", title = "Example Domain"),
                previewTabData(id = "3", title = "GitHub"),
            ),
            listOf(
                previewTabData(id = "3", title = "GitHub"),
            ),
        )
    }
    Box {
        TabsScreenLoadedContent(
            groupedTabs = groupedTabs,
            groups = groups,
            activeGroupIndex = 0,
            selectedTabId = "1",
            snackbarHostState = remember { SnackbarHostState() },
            newTabListener = PreviewNewTabListener,
            onReorderTabs = { _, _, _ -> },
            onReorderGroups = { _, _ -> },
            onGroupSelected = {},
            onGroupPageChanged = {},
            onAddGroup = {},
            onRenameGroup = { _, _ -> },
            onDeleteGroup = {},
            onToggleDefaultGroup = {},
            groupHasPlayingTab = emptyList(),
        )
        Snackbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            action = {
                TextButton(
                    onClick = {},
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SnackbarDefaults.actionContentColor,
                    ),
                ) {
                    Text("戻す")
                }
            },
        ) {
            Text(
                text = "Google",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

sealed interface TabsScreenTestTags {
    val id: String

    val testTag get() = "${TabsScreenTestTags::class.java.name}#$id"

    class Page(index: Int) : TabsScreenTestTags {
        override val id: String = "page_$index"
    }

    class TabGroupTopButton(index: Int) : TabsScreenTestTags {
        override val id: String = "tab_group_$index"
    }

    object AddTabButton : TabsScreenTestTags {
        override val id: String = "add_tab_button"
    }

    object AddTabGroupButton : TabsScreenTestTags {
        override val id: String = "add_tab_group_button"
    }

    class DefaultGroupSwitch(index: Int) : TabsScreenTestTags {
        override val id: String = "default_group_switch_$index"
    }

    class TabItem(index: Int) : TabsScreenTestTags {
        override val id: String = "tab_item_$index"
    }
}

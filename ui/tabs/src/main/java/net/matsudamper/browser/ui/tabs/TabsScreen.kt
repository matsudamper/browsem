package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    onSelectTab: (String) -> Unit,
    onOpenNewTab: (currentGroupId: TabGroupId?) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                onSelectTab = onSelectTab,
                onCloseTab = uiState.callbacks::onCloseTab,
                onOpenNewTab = onOpenNewTab,
                onReorderTabs = uiState.callbacks::onReorderTabs,
                onReorderGroups = uiState.callbacks::onReorderGroups,
                onGroupSelected = uiState.callbacks::onGroupSelected,
                onGroupPageChanged = uiState.callbacks::onGroupPageChanged,
                onAddGroup = uiState.callbacks::onAddGroup,
                onMoveTabToGroup = { tabId, targetGroupIndex ->
                    uiState.callbacks.onMoveTabToGroup(tabId, targetGroupIndex)
                },
                onRenameGroup = uiState.callbacks::onRenameGroup,
                onDeleteGroup = uiState.callbacks::onDeleteGroup,
                onToggleDefaultGroup = uiState.callbacks::onToggleDefaultGroup,
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
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNewTab: (currentGroupId: TabGroupId?) -> Unit,
    onReorderTabs: (groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) -> Unit,
    onReorderGroups: (fromIndex: Int, toIndex: Int) -> Unit,
    onGroupSelected: (Int) -> Unit,
    onGroupPageChanged: (Int) -> Unit,
    onAddGroup: () -> Unit,
    onMoveTabToGroup: (tabId: String, targetGroupIndex: Int) -> Unit,
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

    // ViewModelのactiveGroupIndex変化 → ページスクロールとタブバースクロールを同期
    LaunchedEffect(activeGroupIndex) {
        if (pagerState.currentPage != activeGroupIndex && activeGroupIndex in 0 until groups.size) {
            pagerState.animateScrollToPage(activeGroupIndex)
        }
        if (activeGroupIndex in 0 until groups.size) {
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
            onGroupPageChanged(page)
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
    var moveDialogTabId by remember { mutableStateOf<String?>(null) }

    // 名前変更ダイアログの対象グループインデックス
    var renameDialogGroupIndex by remember { mutableStateOf<Int?>(null) }

    // 削除確認ダイアログの対象グループインデックス
    var deleteDialogGroupIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenNewTab(groups.getOrNull(activeGroupIndex)?.id) },
                modifier = Modifier
                    .testTag(TabsScreenTestTags.AddTabButton.testTag)
                    .padding(16.dp),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "新規タブ")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // グループタブバー（上辺角丸タブ）
            GroupTabBar(
                groups = groups,
                activeGroupIndex = activeGroupIndex,
                pagerState = pagerState,
                highlightedDropTargetIndex = highlightedGroupIndex,
                onGroupSelected = onGroupSelected,
                onReorderGroups = onReorderGroups,
                onAddGroup = onAddGroup,
                onGroupTabBoundsChanged = { index, bounds ->
                    groupTabBounds[index] = bounds
                },
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
                    modifier = Modifier.fillMaxSize()
                        .testTag(TabsScreenTestTags.Page(page).testTag),
                ) {
                    // ページヘッダー: 名前変更・削除ボタン・デフォルトトグル
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        FilledTonalButton(
                            onClick = { renameDialogGroupIndex = page },
                            modifier = Modifier.weight(1f),
                            contentPadding = buttonPadding,
                        ) {
                            Text("名前変更")
                        }
                        FilledTonalButton(
                            onClick = { deleteDialogGroupIndex = page },
                            modifier = Modifier.weight(1f),
                            enabled = groups.size > 1,
                            contentPadding = buttonPadding,
                        ) {
                            Text("削除")
                        }
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
                                checked = groups.getOrNull(page)?.isDefault ?: false,
                                onCheckedChange = { onToggleDefaultGroup(page) },
                                modifier = Modifier.testTag(TabsScreenTestTags.DefaultGroupSwitch(page).testTag),
                            )
                        }
                    }
                    GroupTabGrid(
                        tabs = tabsForPage,
                        selectedTabId = selectedTabId,
                        onSelectTab = onSelectTab,
                        onCloseTab = onCloseTab,
                        onReorderTabs = { from, to -> onReorderTabs(page, from, to) },
                        onTabDragStateChanged = { dragging, centerInRoot ->
                            isTabDragging = dragging
                            tabDragCenterInRoot = centerInRoot
                        },
                        onTabDropped = { tabId ->
                            // ドロップ先のグループタブを判定
                            val targetIndex = groupTabBounds.entries.firstOrNull { (_, bounds) ->
                                bounds.contains(tabDragCenterInRoot)
                            }?.key
                            if (targetIndex != null && targetIndex != page) {
                                onMoveTabToGroup(tabId, targetIndex)
                            }
                        },
                        onTabLongPressWithoutDrag = { tabId ->
                            moveDialogTabId = tabId
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // グループ移動ダイアログ
    val dialogTabId = moveDialogTabId
    if (dialogTabId != null) {
        MoveTabToGroupDialog(
            groups = groups,
            currentGroupIndex = activeGroupIndex,
            onGroupSelected = { targetGroupIndex ->
                onMoveTabToGroup(dialogTabId, targetGroupIndex)
                moveDialogTabId = null
            },
            onDismiss = { moveDialogTabId = null },
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
                TabsScreenTabData(id = "1", title = "Example Domain", previewBitmapArray = null),
                TabsScreenTabData(id = "2", title = "Google", previewBitmapArray = null),
            ),
            listOf(
                TabsScreenTabData(id = "3", title = "GitHub", previewBitmapArray = null),
            ),
        )
    }
    TabsScreenLoadedContent(
        groupedTabs = groupedTabs,
        groups = groups,
        activeGroupIndex = 0,
        selectedTabId = "1",
        onSelectTab = {},
        onCloseTab = {},
        onOpenNewTab = {},
        onReorderTabs = { _, _, _ -> },
        onReorderGroups = { _, _ -> },
        onGroupSelected = {},
        onGroupPageChanged = {},
        onAddGroup = {},
        onMoveTabToGroup = { _, _ -> },
        onRenameGroup = { _, _ -> },
        onDeleteGroup = {},
        onToggleDefaultGroup = {},
    )
}

sealed interface TabsScreenTestTags {
    val id: String

    val testTag get() = "${TabsScreenTestTags::class.java.name}#$id"

    class Page(index: Int): TabsScreenTestTags {
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
}

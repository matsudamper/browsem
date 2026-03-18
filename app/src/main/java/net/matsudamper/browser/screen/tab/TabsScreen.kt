package net.matsudamper.browser.screen.tab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import kotlin.math.abs
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.R
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository

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

/** タブシェイプ：上辺のみ角丸の矩形 */
private val TabShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

/** グループタブバー全体の高さ。LazyRow・外側 Box 両方で共有する */
private val GroupTabBarHeight = 48.dp

/** 非選択タブの最小高さ。選択タブは GroupTabBarHeight まで伸びて「浮き上がり」を表現する */
private val GroupTabUnselectedHeight = 40.dp

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
internal fun TabsScreen(
    browserSessionController: BrowserSessionController,
    tabGroupRepository: TabGroupRepository,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(initializer = {
        TabsScreenViewModel(
            tabStore = browserSessionController,
            tabGroupRepository = tabGroupRepository,
        )
    })
    val groupedTabs by viewModel.groupedTabs.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val activeGroupIndex = viewModel.activeGroupIndex.collectAsState().value

    if (activeGroupIndex == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        TabsScreenContent(
            groupedTabs = groupedTabs,
            groups = groups,
            activeGroupIndex = activeGroupIndex,
            selectedTabId = selectedTabId,
            onSelectTab = onSelectTab,
            onCloseTab = { tabId ->
                viewModel.onTabClosed(tabId)
                onCloseTab(tabId)
            },
            onOpenNewTab = onOpenNewTab,
            onReorderTabs = viewModel::reorderTabs,
            onReorderGroups = viewModel::reorderGroups,
            onGroupSelected = viewModel::onGroupSelected,
            onGroupPageChanged = viewModel::onGroupPageChanged,
            onAddGroup = viewModel::addGroup,
            onMoveTabToGroup = { tabId, targetGroupIndex -> viewModel.moveTabToGroup(tabId, targetGroupIndex) },
            onRenameGroup = viewModel::renameGroup,
            onDeleteGroup = viewModel::deleteGroup,
            modifier = modifier,
        )
    }

}

/** ドラッグ&ドロップの状態を管理するクラス */
private class DragDropState(
    val gridState: LazyGridState,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    /** ドラッグ中のアイテムのキー */
    var draggedItemKey: Any? by mutableStateOf(null)
        private set

    /** グリッドのビューポート座標でのドラッグオーバーレイの左上位置 */
    var draggedItemOffset: IntOffset by mutableStateOf(IntOffset.Zero)
        private set

    /** ドラッグ中アイテムのサイズ（ピクセル） */
    var draggedItemSize: IntSize by mutableStateOf(IntSize.Zero)
        private set

    /** ドラッグ中の現在のインデックス（並び替え時に更新） */
    private var currentDragIndex: Int by mutableIntStateOf(-1)

    /** ドラッグ中かどうか */
    val isDragging: Boolean get() = draggedItemKey != null

    /** ドラッグ開始からの累積移動距離（ピクセル） */
    private var totalDragDistance: Float = 0f

    /** ルート座標でのドラッグ中の中心位置（グループ間移動の衝突判定用） */
    var dragCenterInRoot: Offset by mutableStateOf(Offset.Zero)
        private set

    /** グリッドのルート座標上の bounds（onGloballyPositioned で設定） */
    var gridBoundsInRoot: Rect by mutableStateOf(Rect.Zero)

    /** ドラッグ開始時の処理 */
    fun onDragStart(offset: Offset) {
        val viewportOffset = gridState.layoutInfo.viewportStartOffset
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            // visibleItemsInfo の offset は絶対座標なのでビューポート相対に変換して比較する
            val itemTop = info.offset.y - viewportOffset
            val itemBottom = itemTop + info.size.height
            val itemLeft = info.offset.x.toFloat()
            val itemRight = itemLeft + info.size.width
            offset.x >= itemLeft && offset.x <= itemRight &&
                    offset.y >= itemTop && offset.y <= itemBottom
        } ?: return

        draggedItemKey = item.key
        draggedItemOffset = IntOffset(item.offset.x, item.offset.y - viewportOffset)
        draggedItemSize = item.size
        currentDragIndex = item.index
        totalDragDistance = 0f

        // ルート座標での中心位置を初期化
        updateDragCenterInRoot()
    }

    /** ドラッグ中の移動処理 */
    fun onDrag(dragAmount: Offset) {
        if (!isDragging) return

        draggedItemOffset = (draggedItemOffset.toOffset() + dragAmount).round()
        totalDragDistance += dragAmount.getDistance()

        // ルート座標を更新
        updateDragCenterInRoot()

        // ドラッグ中アイテムの中心座標（ビューポート相対）
        val centerX = draggedItemOffset.x + draggedItemSize.width / 2f
        val centerY = draggedItemOffset.y + draggedItemSize.height / 2f

        val viewportOffset = gridState.layoutInfo.viewportStartOffset

        // 中心に最も近い別のアイテムを探す
        val targetItem = gridState.layoutInfo.visibleItemsInfo
            .filter { it.key != draggedItemKey }
            .minByOrNull { info ->
                val itemTop = info.offset.y - viewportOffset
                val itemCenterX = info.offset.x + info.size.width / 2f
                val itemCenterY = itemTop + info.size.height / 2f
                val dx = centerX - itemCenterX
                val dy = centerY - itemCenterY
                dx * dx + dy * dy
            } ?: return

        // ドラッグ中アイテムの中心が別のアイテムの領域内に入ったら並び替え
        val targetTop = (targetItem.offset.y - viewportOffset).toFloat()
        val targetBottom = targetTop + targetItem.size.height
        val targetLeft = targetItem.offset.x.toFloat()
        val targetRight = targetLeft + targetItem.size.width

        if (centerX in targetLeft..targetRight &&
            centerY in targetTop..targetBottom &&
            targetItem.index != currentDragIndex
        ) {
            onMove(currentDragIndex, targetItem.index)
            currentDragIndex = targetItem.index
        }
    }

    /** ドラッグ終了時の処理。ドラッグされていたアイテムのキーと移動したかどうかを返す。 */
    fun onDragEnd(): DragEndResult? {
        val key = draggedItemKey ?: return null
        val didMove = totalDragDistance > DRAG_THRESHOLD
        draggedItemKey = null
        draggedItemOffset = IntOffset.Zero
        draggedItemSize = IntSize.Zero
        currentDragIndex = -1
        dragCenterInRoot = Offset.Zero
        totalDragDistance = 0f
        return DragEndResult(key = key, didMove = didMove)
    }

    data class DragEndResult(
        val key: Any,
        val didMove: Boolean,
    )

    companion object {
        /** ドラッグと判定する最低移動距離（ピクセル） */
        private const val DRAG_THRESHOLD = 20f
    }

    private fun updateDragCenterInRoot() {
        val centerX = draggedItemOffset.x + draggedItemSize.width / 2f
        val centerY = draggedItemOffset.y + draggedItemSize.height / 2f
        dragCenterInRoot = Offset(
            x = gridBoundsInRoot.left + centerX,
            y = gridBoundsInRoot.top + centerY,
        )
    }
}

@Composable
private fun rememberDragDropState(
    gridState: LazyGridState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropState {
    return remember(gridState) {
        DragDropState(gridState = gridState, onMove = onMove)
    }
}

/** グループタブバーのドラッグ&ドロップ状態を管理するクラス */
private class GroupDragDropState(
    val listState: LazyListState,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    /** ドラッグ中のグループの key（group.id.value） */
    var draggedItemKey: Any? by mutableStateOf(null)
        private set

    /** LazyRow ビューポート座標でのオーバーレイ左上位置 */
    var draggedItemOffset: IntOffset by mutableStateOf(IntOffset.Zero)
        private set

    /** ドラッグ中アイテムのサイズ（ピクセル） */
    var draggedItemSize: IntSize by mutableStateOf(IntSize.Zero)
        private set

    /** 並び替え追跡用の現在インデックス */
    private var currentDragIndex: Int by mutableIntStateOf(-1)

    val isDragging: Boolean get() = draggedItemKey != null

    /** ドラッグ開始時の処理。groupCount は追加ボタンを除いたグループ数。 */
    fun onDragStart(offset: Offset, groupCount: Int) {
        // info.offset はコンテンツ領域先頭（contentPadding 以降）からの相対座標。
        // viewportStartOffset = -contentPadding.start のため引き算で描画座標に変換する。
        val viewportOffset = listState.layoutInfo.viewportStartOffset
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val itemLeft = (info.offset - viewportOffset).toFloat()
            val itemRight = itemLeft + info.size
            info.index < groupCount && offset.x >= itemLeft && offset.x <= itemRight
        } ?: return

        draggedItemKey = item.key
        draggedItemOffset = IntOffset(item.offset - viewportOffset, 0)
        draggedItemSize = IntSize(item.size, listState.layoutInfo.viewportSize.height)
        currentDragIndex = item.index
    }

    /** ドラッグ中の移動処理 */
    fun onDrag(dragAmount: Offset, groupCount: Int) {
        if (!isDragging) return
        draggedItemOffset = (draggedItemOffset.toOffset() + dragAmount).round()

        val centerX = draggedItemOffset.x + draggedItemSize.width / 2f
        val viewportOffset = listState.layoutInfo.viewportStartOffset

        val targetItem = listState.layoutInfo.visibleItemsInfo
            .filter { it.key != draggedItemKey && it.index < groupCount }
            .minByOrNull { info ->
                val itemCenterX = (info.offset - viewportOffset).toFloat() + info.size / 2f
                abs(centerX - itemCenterX)
            } ?: return

        val targetLeft = (targetItem.offset - viewportOffset).toFloat()
        val targetRight = targetLeft + targetItem.size

        if (centerX in targetLeft..targetRight && targetItem.index != currentDragIndex) {
            onMove(currentDragIndex, targetItem.index)
            currentDragIndex = targetItem.index
        }
    }

    /** ドラッグ終了時の処理 */
    fun onDragEnd() {
        draggedItemKey = null
        draggedItemOffset = IntOffset.Zero
        draggedItemSize = IntSize.Zero
        currentDragIndex = -1
    }
}

@Composable
private fun rememberGroupDragDropState(
    listState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): GroupDragDropState {
    return remember(listState) {
        GroupDragDropState(listState = listState, onMove = onMove)
    }
}

@Composable
internal fun TabsScreenContent(
    groupedTabs: List<List<TabsScreenTabData>>,
    groups: List<TabGroupData>,
    activeGroupIndex: Int,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNewTab: () -> Unit,
    onReorderTabs: (groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) -> Unit,
    onReorderGroups: (fromIndex: Int, toIndex: Int) -> Unit,
    onGroupSelected: (Int) -> Unit,
    onGroupPageChanged: (Int) -> Unit,
    onAddGroup: () -> Unit,
    onMoveTabToGroup: (tabId: String, targetGroupIndex: Int) -> Unit,
    onRenameGroup: (groupIndex: Int, newName: String) -> Unit,
    onDeleteGroup: (groupIndex: Int) -> Unit,
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

    var pagerBounds by remember { mutableStateOf(Rect.Zero) }

    // ドラッグ中に端に近づいたら Pager をスクロールする
    LaunchedEffect(isTabDragging) {
        if (!isTabDragging) return@LaunchedEffect
        while (isTabDragging) {
            if (pagerBounds != Rect.Zero) {
                val x = tabDragCenterInRoot.x
                val y = tabDragCenterInRoot.y
                val threshold = with(density) { 48.dp.toPx() }

                if (y >= pagerBounds.top && y <= pagerBounds.bottom) {
                    if (x < pagerBounds.left + threshold && pagerState.currentPage > 0) {
                        // 左端
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        kotlinx.coroutines.delay(300) // スクロール後の連続発火を防ぐ
                        continue
                    } else if (x > pagerBounds.right - threshold && pagerState.currentPage < pagerState.pageCount - 1) {
                        // 右端
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        kotlinx.coroutines.delay(300) // スクロール後の連続発火を防ぐ
                        continue
                    }
                }
            }
            kotlinx.coroutines.delay(16) // 次のフレームまで待機
        }
    }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        pagerBounds = coordinates.boundsInRoot()
                    },
                userScrollEnabled = !isTabDragging,
            ) { page ->
                val tabsForPage = groupedTabs.getOrElse(page) { emptyList() }
                Column(modifier = Modifier.fillMaxSize()) {
                    // ページヘッダー: 名前変更・削除ボタン
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { renameDialogGroupIndex = page },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("名前変更")
                        }
                        FilledTonalButton(
                            onClick = { deleteDialogGroupIndex = page },
                            modifier = Modifier.weight(1f),
                            enabled = groups.size > 1,
                        ) {
                            Text("削除")
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

        FloatingActionButton(
            onClick = onOpenNewTab,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24dp),
                contentDescription = "新規タブ",
            )
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
 * グループタブバー。
 * 栞形のタブを横並びに表示し、末尾に追加ボタンを配置する。
 * 選択中のタブが手前に表示され、非選択タブは下に沈んで奥にあるように見える。
 * 長押しドラッグでグループの順序を入れ替えられる。
 * タブドラッグ中のドロップターゲットをハイライト表示する。
 */
@Composable
private fun GroupTabBar(
    groups: List<TabGroupData>,
    activeGroupIndex: Int,
    pagerState: PagerState,
    highlightedDropTargetIndex: Int?,
    onGroupSelected: (Int) -> Unit,
    onReorderGroups: (fromIndex: Int, toIndex: Int) -> Unit,
    onAddGroup: () -> Unit,
    onGroupTabBoundsChanged: (index: Int, bounds: Rect) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val dragDropState = rememberGroupDragDropState(
        listState = listState,
        onMove = onReorderGroups,
    )

    // ページスクロール進捗を読み取る（タブの高さ・色アニメーションに使用）
    val currentPage = pagerState.currentPage
    val offsetFraction = pagerState.currentPageOffsetFraction

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        LazyRow(
            state = listState,
            // start padding を contentPadding に移すことで、スクロール時に左端まで表示できるようにする
            contentPadding = PaddingValues(start = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                // 全アイテムが常に GroupTabBarHeight のwrapperを持つため、高さは固定で問題なし
                .height(GroupTabBarHeight)
                .pointerInput(dragDropState) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            dragDropState.onDragStart(offset, groups.size)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDropState.onDrag(dragAmount, groups.size)
                        },
                        onDragEnd = { dragDropState.onDragEnd() },
                        onDragCancel = { dragDropState.onDragEnd() },
                    )
                },
        ) {
            itemsIndexed(
                items = groups,
                key = { _, group -> group.id.value },
            ) { index, group ->
                // ページスクロール進捗に応じた選択強度（0=非選択, 1=選択）
                val selectionFraction = when {
                    index == currentPage -> 1f - abs(offsetFraction)
                    index == currentPage + 1 && offsetFraction > 0f -> offsetFraction
                    index == currentPage - 1 && offsetFraction < 0f -> -offsetFraction
                    else -> 0f
                }
                val isDropTarget = index == highlightedDropTargetIndex
                val isDraggingThis = dragDropState.draggedItemKey == group.id.value
                GroupBookmarkTab(
                    label = group.name,
                    selectionFraction = selectionFraction,
                    isDropTarget = isDropTarget,
                    onClick = { onGroupSelected(index) },
                    modifier = Modifier
                        .animateItem()
                        .zIndex(if (index == activeGroupIndex) groups.size.toFloat() else index.toFloat())
                        .then(if (isDraggingThis) Modifier.alpha(0f) else Modifier)
                        .onGloballyPositioned { coordinates ->
                            onGroupTabBoundsChanged(index, coordinates.boundsInRoot())
                        },
                )
            }
            // グループ追加ボタン（ドラッグ対象外）
            item(key = "add_group") {
                AddGroupBookmarkTab(onClick = onAddGroup)
            }
        }

        // ドラッグ中のオーバーレイ表示
        if (dragDropState.isDragging) {
            val draggedGroup = groups.firstOrNull { it.id.value == dragDropState.draggedItemKey }
            if (draggedGroup != null) {
                GroupBookmarkTab(
                    label = draggedGroup.name,
                    selectionFraction = 1f, // 持ち上がった状態なので選択扱いでエレベーションを高くする
                    isDropTarget = false,
                    onClick = {},
                    modifier = Modifier
                        .offset { dragDropState.draggedItemOffset }
                        .zIndex(Float.MAX_VALUE),
                )
            }
        }
    }
}

/**
 * 栞形のグループタブ。
 * 選択中: 手前に表示・強調色。非選択: 下にオフセットして奥に見える。
 * ドロップターゲット時: 境界線で強調表示。
 */
@Composable
private fun GroupBookmarkTab(
    label: String,
    selectionFraction: Float,
    isDropTarget: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val unselectedColor = MaterialTheme.colorScheme.surfaceVariant
    val dropTargetColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    val fraction = selectionFraction.coerceIn(0f, 1f)
    val backgroundColor = when {
        isDropTarget -> dropTargetColor
        else -> lerp(unselectedColor, selectedColor, fraction)
    }

    // ページスクロール進捗に応じて高さをアニメーションする
    val visualHeight = GroupTabUnselectedHeight + (GroupTabBarHeight - GroupTabUnselectedHeight) * fraction
    // 外側のBoxは常に GroupTabBarHeight を確保し、LazyRowアイテムの位置が変わらないようにする
    Box(
        modifier = modifier
            .width(120.dp)
            .height(GroupTabBarHeight),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = visualHeight)
                .graphicsLayer {
                    shadowElevation = when {
                        isDropTarget -> with(density) { 12.dp.toPx() }
                        else -> {
                            val minShadow = with(density) { 2.dp.toPx() }
                            val maxShadow = with(density) { 8.dp.toPx() }
                            minShadow + (maxShadow - minShadow) * fraction
                        }
                    }
                    shape = TabShape
                    clip = true
                }
                .background(
                    color = backgroundColor,
                    shape = TabShape,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = if (isDropTarget) selectedTextColor else lerp(unselectedTextColor, selectedTextColor, fraction),
            )
        }
    }
}

/** 栞形のグループ追加ボタン（"+" アイコン） */
@Composable
private fun AddGroupBookmarkTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // GroupBookmarkTab と同じ GroupTabBarHeight 外側 Box + BottomCenter 揃えで浮きを防ぐ
    Box(
        modifier = modifier
            .width(56.dp)
            .height(GroupTabBarHeight),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GroupTabUnselectedHeight)
                .graphicsLayer {
                    shadowElevation = with(density) { 2.dp.toPx() }
                    shape = TabShape
                    clip = true
                }
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = TabShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24dp),
                contentDescription = "グループを追加",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
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

/**
 * グループ内のタブグリッド。
 * ドラッグ&ドロップによる並び替えをサポートする。
 * タブをグループタブバーへドラッグすることでグループ間移動もできる。
 */
@Composable
internal fun GroupTabGrid(
    tabs: List<TabsScreenTabData>,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onReorderTabs: (fromIndex: Int, toIndex: Int) -> Unit,
    onTabDragStateChanged: (isDragging: Boolean, centerInRoot: Offset) -> Unit,
    onTabDropped: (tabId: String) -> Unit,
    onTabLongPressWithoutDrag: (tabId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("タブがありません")
        }
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val columns = TabsLayoutDefaults.calculateColumns(maxWidth)
        val gridState = rememberLazyGridState()
        val dragDropState = rememberDragDropState(
            gridState = gridState,
            onMove = onReorderTabs,
        )

        // ドラッグ状態を上位コンポーザブルに通知する
        LaunchedEffect(dragDropState.isDragging, dragDropState.dragCenterInRoot) {
            onTabDragStateChanged(dragDropState.isDragging, dragDropState.dragCenterInRoot)
        }

        LaunchedEffect(Unit) {
            val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
            if (selectedIndex >= 0) {
                val targetRow = selectedIndex / columns
                gridState.scrollToItem(targetRow * columns)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    dragDropState.gridBoundsInRoot = coordinates.boundsInRoot()
                }
                .pointerInput(dragDropState) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            dragDropState.onDragStart(offset)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDropState.onDrag(dragAmount)
                        },
                        onDragEnd = {
                            val result = dragDropState.onDragEnd()
                            if (result != null) {
                                val tabId = result.key as String
                                if (result.didMove) {
                                    onTabDropped(tabId)
                                } else {
                                    onTabLongPressWithoutDrag(tabId)
                                }
                            }
                        },
                        onDragCancel = { dragDropState.onDragEnd() },
                    )
                },
            contentPadding = PaddingValues(TabsLayoutDefaults.gridPadding),
            verticalArrangement = Arrangement.spacedBy(TabsLayoutDefaults.gridSpacing),
            horizontalArrangement = Arrangement.spacedBy(TabsLayoutDefaults.gridSpacing),
        ) {
            items(
                items = tabs,
                key = { tab -> tab.id },
            ) { tab ->
                val selected = tab.id == selectedTabId
                // ドラッグ中のアイテムはグリッド上で非表示（透明）にする
                val isDraggingThis = dragDropState.draggedItemKey == tab.id
                TabCard(
                    tab = tab,
                    selected = selected,
                    onSelectTab = onSelectTab,
                    onCloseTab = onCloseTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(TabsLayoutDefaults.cardAspectRatio)
                        .animateItem()
                        .then(if (isDraggingThis) Modifier.alpha(0f) else Modifier),
                )
            }
        }

        // ドラッグ中のオーバーレイ表示
        if (dragDropState.isDragging) {
            val overlayTab = tabs.firstOrNull { it.id == dragDropState.draggedItemKey }
            if (overlayTab != null) {
                val density = LocalDensity.current
                val widthDp = with(density) { dragDropState.draggedItemSize.width.toDp() }
                val heightDp = with(density) { dragDropState.draggedItemSize.height.toDp() }
                Box(
                    modifier = Modifier
                        .offset { dragDropState.draggedItemOffset }
                        .size(width = widthDp, height = heightDp)
                        .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp)),
                ) {
                    TabCard(
                        tab = overlayTab,
                        selected = overlayTab.id == selectedTabId,
                        onSelectTab = {},
                        onCloseTab = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** タブカード */
@Composable
private fun TabCard(
    tab: TabsScreenTabData,
    selected: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onSelectTab(tab.id) },
        modifier = modifier,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 1.dp
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onCloseTab(tab.id) },
                    modifier = Modifier.offset { IntOffset(4, -4) },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close_24dp),
                        contentDescription = "close"
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                var bitmap: Bitmap? by remember { mutableStateOf(null) }
                LaunchedEffect(tab.previewBitmapArray) {
                    val array = tab.previewBitmapArray ?: return@LaunchedEffect
                    bitmap = BitmapFactory.decodeByteArray(array, 0, array.size)
                }

                val preview = bitmap?.asImageBitmap()
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "Tab preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "No Preview",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * タブの移動先グループを選択するダイアログ。
 * 現在所属しているグループ以外のグループを一覧表示し、
 * タップで選択するとそのグループへタブを移動する。
 */
@Composable
private fun MoveTabToGroupDialog(
    groups: List<TabGroupData>,
    currentGroupIndex: Int,
    onGroupSelected: (targetGroupIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("グループに移動")
        },
        text = {
            Column {
                groups.forEachIndexed { index, group ->
                    if (index == currentGroupIndex) return@forEachIndexed
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGroupSelected(index) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

/** グループ名を変更するダイアログ */
@Composable
private fun RenameGroupDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("グループ名を変更") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("グループ名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("変更")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

/** グループを削除する前の確認ダイアログ */
@Composable
private fun DeleteGroupDialog(
    groupName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("グループを削除") },
        text = { Text("「${groupName}」を削除しますか？グループ内のタブは別のグループへ移動されます。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("削除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
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
    TabsScreenContent(
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
    )
}

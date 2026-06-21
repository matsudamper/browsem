package net.matsudamper.browser.ui.tabs

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset


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
    floatingActionButtonBoundsInRoot: Rect?,
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
        // pointerInput(dragDropState) は onTabDropped / onTabLongPressWithoutDrag の
        // ラムダ差し替えでは再起動しないため、最新のラムダを参照できるようにしておく。
        val currentOnTabDropped by rememberUpdatedState(onTabDropped)
        val currentOnTabLongPressWithoutDrag by rememberUpdatedState(onTabLongPressWithoutDrag)
        var gridBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
        val density = LocalDensity.current
        val floatingActionButtonBottomPadding = remember(
            density,
            floatingActionButtonBoundsInRoot,
            gridBoundsInRoot,
        ) {
            if (floatingActionButtonBoundsInRoot == null || !floatingActionButtonBoundsInRoot.overlapsHorizontally(gridBoundsInRoot)) {
                0.dp
            } else {
                with(density) {
                    (gridBoundsInRoot.bottom - floatingActionButtonBoundsInRoot.top).coerceAtLeast(0f).toDp()
                }
            }
        }
        // デコード済み Bitmap を保持してスクロール往復時の再デコードを避ける。
        // 画面を離れる（GroupTabGrid がコンポジションから抜ける）と破棄される。
        // キーは TabPreviewImage（contentEquals/contentHashCode 実装済み）なので
        // プレビュー内容が変わった際は自然にキャッシュミスする。
        val bitmapCache = remember {
            object : LruCache<TabPreviewImage, Bitmap>(TAB_BITMAP_CACHE_BYTES) {
                override fun sizeOf(key: TabPreviewImage, value: Bitmap): Int = value.byteCount
            }
        }

        // ドラッグ状態を上位コンポーザブルに通知する
        LaunchedEffect(dragDropState.isDragging, dragDropState.dragCenterInRoot, onTabDragStateChanged) {
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
                    val boundsInRoot = coordinates.boundsInRoot()
                    dragDropState.gridBoundsInRoot = boundsInRoot
                    gridBoundsInRoot = boundsInRoot
                }
                .pointerInput(dragDropState) {
                    // onDragEnd / onDragCancel どちらでも同じロジックでメニュー判定する。
                    // Compose の detectDragGesturesAfterLongPress は親（Pager 等）が pointer event を
                    // 消費すると onDragCancel を呼ぶため、cancel 側で何もしないと
                    // 長押し→指を離すという同じ操作でもメニューが出たり出なかったりする。
                    val handleEnd: () -> Unit = {
                        val result = dragDropState.endDrag()
                        val tabId = result?.key as? String
                        if (result != null && tabId != null) {
                            // メニューを開く条件:
                            //   - 並び替え未発生（タブが動いていない）
                            //   - ドラッグ中心がグリッド内（グループバー上でリリースしていない）
                            //   - 押してから 2 秒以内に離した
                            val shouldShowMenu = !result.didReorder &&
                                    result.releasedInsideGrid &&
                                    result.elapsedMs <= MENU_RELEASE_WINDOW_MS
                            if (shouldShowMenu) {
                                currentOnTabLongPressWithoutDrag(tabId)
                            } else {
                                // 別グループへホバー中ならクロスグループ移動が走る。
                                // それ以外（並び替え済みでグリッド内に戻したケース等）は no-op。
                                currentOnTabDropped(tabId)
                            }
                        }
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            dragDropState.onDragStart(offset)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDropState.onDrag(dragAmount)
                        },
                        onDragEnd = handleEnd,
                        onDragCancel = handleEnd,
                    )
                },
            contentPadding = PaddingValues(
                start = TabsLayoutDefaults.gridPadding,
                top = TabsLayoutDefaults.gridPadding,
                end = TabsLayoutDefaults.gridPadding,
                bottom = TabsLayoutDefaults.gridPadding + floatingActionButtonBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(TabsLayoutDefaults.gridSpacing),
            horizontalArrangement = Arrangement.spacedBy(TabsLayoutDefaults.gridSpacing),
        ) {
            itemsIndexed(
                items = tabs,
                key = { _, tab -> tab.id },
            ) { index, tab ->
                val selected = tab.id == selectedTabId
                // ドラッグ中のアイテムはグリッド上で非表示（透明）にする
                val isDraggingThis = dragDropState.draggedItemKey == tab.id
                TabCard(
                    tab = tab,
                    selected = selected,
                    onSelectTab = { tabId ->
                        // 長押し(ドラッグ)モード中はタブ選択をブロックする
                        if (!dragDropState.isDragging) onSelectTab(tabId)
                    },
                    onCloseTab = onCloseTab,
                    bitmapCache = bitmapCache,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(TabsLayoutDefaults.cardAspectRatio)
                        .testTag(TabsScreenTestTags.TabItem(index).testTag)
                        .animateItem()
                        .then(if (isDraggingThis) Modifier.alpha(0f) else Modifier),
                )
            }
        }

        // ドラッグ中のオーバーレイ表示
        if (dragDropState.isDragging) {
            val overlayTab = tabs.firstOrNull { it.id == dragDropState.draggedItemKey }
            if (overlayTab != null) {
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
                        bitmapCache = bitmapCache,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun Rect.overlapsHorizontally(other: Rect): Boolean {
    return left < other.right && right > other.left
}

// タブサムネイルキャッシュの上限（バイト）。16MiB。
// タブカードは inSampleSize=2 でデコードするため、1枚あたり数百KB程度。
// 典型的なタブ数（〜50）をカバーできる容量。
private const val TAB_BITMAP_CACHE_BYTES: Int = 16 * 1024 * 1024

// 長押し開始から離すまでの時間がこの値以内かつタブが移動していなければ移動メニューを表示する。
private const val MENU_RELEASE_WINDOW_MS: Long = 2_000L


@Composable
private fun rememberDragDropState(
    gridState: LazyGridState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropState {
    return remember(gridState) {
        DragDropState(gridState = gridState, onMove = onMove)
    }
}

/** ドラッグ&ドロップの状態を管理するクラス */
@Stable
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

    /** このドラッグ中に並び替えが発生したか（onMove が一度でも呼ばれたか） */
    private var didReorder: Boolean = false

    /** ドラッグ開始時刻（uptimeMillis） */
    private var dragStartUptimeMs: Long = 0L

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
        didReorder = false
        dragStartUptimeMs = android.os.SystemClock.uptimeMillis()

        // ルート座標での中心位置を初期化
        updateDragCenterInRoot()
    }

    /** ドラッグ中の移動処理 */
    fun onDrag(dragAmount: Offset) {
        if (!isDragging) return

        draggedItemOffset = (draggedItemOffset.toOffset() + dragAmount).round()

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
            didReorder = true
        }
    }

    /** ドラッグ状態をリセットし、終了時点の情報を返す。 */
    fun endDrag(): EndResult? {
        val key = draggedItemKey ?: return null
        val elapsed = android.os.SystemClock.uptimeMillis() - dragStartUptimeMs
        val insideGrid = if (gridBoundsInRoot.isEmpty) true else gridBoundsInRoot.contains(dragCenterInRoot)
        val moved = didReorder
        draggedItemKey = null
        draggedItemOffset = IntOffset.Zero
        draggedItemSize = IntSize.Zero
        currentDragIndex = -1
        dragCenterInRoot = Offset.Zero
        didReorder = false
        dragStartUptimeMs = 0L
        return EndResult(
            key = key,
            didReorder = moved,
            releasedInsideGrid = insideGrid,
            elapsedMs = elapsed,
        )
    }

    data class EndResult(
        val key: Any,
        val didReorder: Boolean,
        val releasedInsideGrid: Boolean,
        val elapsedMs: Long,
    )

    private fun updateDragCenterInRoot() {
        val centerX = draggedItemOffset.x + draggedItemSize.width / 2f
        val centerY = draggedItemOffset.y + draggedItemSize.height / 2f
        dragCenterInRoot = Offset(
            x = gridBoundsInRoot.left + centerX,
            y = gridBoundsInRoot.top + centerY,
        )
    }
}

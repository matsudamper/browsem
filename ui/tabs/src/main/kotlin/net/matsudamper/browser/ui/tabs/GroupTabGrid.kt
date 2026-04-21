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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
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
                    onSelectTab = { tabId ->
                        // 長押し(ドラッグ)モード中はタブ選択をブロックする
                        if (!dragDropState.isDragging) onSelectTab(tabId)
                    },
                    onCloseTab = onCloseTab,
                    bitmapCache = bitmapCache,
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
                        bitmapCache = bitmapCache,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// タブサムネイルキャッシュの上限（バイト）。16MiB。
// タブカードは inSampleSize=2 でデコードするため、1枚あたり数百KB程度。
// 典型的なタブ数（〜50）をカバーできる容量。
private const val TAB_BITMAP_CACHE_BYTES: Int = 16 * 1024 * 1024


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

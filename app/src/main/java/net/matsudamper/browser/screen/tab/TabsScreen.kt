package net.matsudamper.browser.screen.tab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.lifecycle.viewmodel.compose.viewModel
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.R

internal object TabsLayoutDefaults {
    val minCellWidth: Dp = 220.dp
    val gridPadding: Dp = 12.dp
    val gridSpacing: Dp = 12.dp
    val topBarHeight: Dp = 56.dp
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

@Composable
internal fun TabsScreen(
    browserSessionController: BrowserSessionController,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(initializer = {
        TabsScreenViewModel(
            browserSessionController = browserSessionController,
        )
    })
    val tabs by viewModel.tabs.collectAsState()
    TabsScreenContent(
        tabs = tabs,
        selectedTabId = selectedTabId,
        onSelectTab = onSelectTab,
        onCloseTab = onCloseTab,
        onOpenNewTab = onOpenNewTab,
        onReorderTabs = viewModel::reorderTabs,
        modifier = modifier,
    )
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
    }

    /** ドラッグ中の移動処理 */
    fun onDrag(dragAmount: Offset) {
        if (!isDragging) return

        draggedItemOffset = (draggedItemOffset.toOffset() + dragAmount).round()

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

    /** ドラッグ終了時の処理 */
    fun onDragEnd() {
        draggedItemKey = null
        draggedItemOffset = IntOffset.Zero
        draggedItemSize = IntSize.Zero
        currentDragIndex = -1
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

@Composable
private fun TabsScreenContent(
    tabs: List<TabsScreenTabData>,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNewTab: () -> Unit,
    onReorderTabs: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TabsLayoutDefaults.topBarHeight)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tabs",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("タブがありません")
                }
                return
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val columns = TabsLayoutDefaults.calculateColumns(maxWidth)
                val gridState = rememberLazyGridState()
                val dragDropState = rememberDragDropState(
                    gridState = gridState,
                    onMove = onReorderTabs,
                )

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
                        .pointerInput(dragDropState) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    dragDropState.onDragStart(offset)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDropState.onDrag(dragAmount)
                                },
                                onDragEnd = { dragDropState.onDragEnd() },
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

@Composable
@Preview
private fun Preview() {
    val tabs = remember {
        listOf(
            TabsScreenTabData(
                id = 1L.toString(),
                title = "Example Domain",
                previewBitmapArray = null,
            ),
            TabsScreenTabData(
                id = 2L.toString(),
                title = "Google",
                previewBitmapArray = null,
            ),
            TabsScreenTabData(
                id = 3L.toString(),
                title = "GitHub: Let's build from here",
                previewBitmapArray = null,
            ),
        )
    }
    TabsScreenContent(
        tabs = tabs,
        selectedTabId = 1L.toString(),
        onSelectTab = {},
        onCloseTab = {},
        onOpenNewTab = {},
        onReorderTabs = { _, _ -> },
    )
}

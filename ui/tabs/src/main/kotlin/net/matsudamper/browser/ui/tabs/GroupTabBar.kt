package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import net.matsudamper.browser.data.TabGroupData
import kotlin.math.abs


/** タブシェイプ：上辺のみ角丸の矩形 */
private val TabShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

/** グループタブバー全体の高さ。LazyRow・外側 Box 両方で共有する */
private val GroupTabBarHeight = 48.dp

/** 非選択タブの最小高さ。選択タブは GroupTabBarHeight まで伸びて「浮き上がり」を表現する */
private val GroupTabUnselectedHeight = 40.dp

/** 隣のスロットへ移るために必要な移動量（タブ幅に対する割合） */
internal const val GroupReorderSlotThreshold = 0.55f

/**
 * グループタブバー。
 * 栞形のタブを横並びに表示し、末尾に追加ボタンを配置する。
 * 選択中のタブが手前に表示され、非選択タブは下に沈んで奥にあるように見える。
 * 長押しドラッグでグループの順序を入れ替えられる。
 * タブドラッグ中のドロップターゲットをハイライト表示する。
 *
 * 並び替え判定は LazyRow の layoutInfo（animateItem 中は中間座標になる）を使わず、
 * ドラッグ開始位置からの移動量と固定タブ幅だけで目標インデックスを決める。
 */
@Composable
internal fun GroupTabBar(
    groups: List<TabGroupData>,
    activeGroupIndex: Int,
    pagerState: PagerState,
    highlightedDropTargetIndex: Int?,
    groupHasPlayingTab: List<Boolean>,
    onGroupSelected: (Int) -> Unit,
    onReorderGroups: (fromIndex: Int, toIndex: Int) -> Unit,
    onReorderDragStateChanged: (Boolean) -> Unit = {},
    onAddGroup: () -> Unit,
    onGroupTabBoundsChanged: (index: Int, bounds: Rect) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val dragDropState = rememberGroupDragDropState(
        listState = listState,
        onMove = onReorderGroups,
        onDragStateChanged = onReorderDragStateChanged,
    )

    // ページスクロール進捗を読み取る（タブの高さ・色アニメーションに使用）。
    // グループ並び替え中は Pager の中間オフセットに追従させるとインジケータが振動するため、
    // 選択中グループの論理インデックスに固定する。
    val currentPage: Int
    val offsetFraction: Float
    if (dragDropState.isDragging) {
        currentPage = activeGroupIndex
        offsetFraction = 0f
    } else {
        currentPage = pagerState.currentPage
        offsetFraction = pagerState.currentPageOffsetFraction
    }

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
                // groups.size をキーに含めないと、グループ追加後も古い groupCount が
                // クロージャに残り、新規グループ（index == 旧 size）がドラッグ対象外になる
                .pointerInput(dragDropState, groups.size) {
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
                    isPlaying = groupHasPlayingTab.getOrElse(index) { false },
                    onClick = { onGroupSelected(index) },
                    modifier = Modifier
                        .testTag(TabsScreenTestTags.TabGroupTopButton(index).testTag)
                        .semantics {
                            selected = index == activeGroupIndex
                        }
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
            val draggedIndex = groups.indexOfFirst { it.id.value == dragDropState.draggedItemKey }
            if (draggedGroup != null) {
                GroupBookmarkTab(
                    label = draggedGroup.name,
                    selectionFraction = 1f, // 持ち上がった状態なので選択扱いでエレベーションを高くする
                    isDropTarget = false,
                    isPlaying = groupHasPlayingTab.getOrElse(draggedIndex) { false },
                    onClick = {},
                    modifier = Modifier
                        .offset { dragDropState.draggedItemOffset }
                        .zIndex(Float.MAX_VALUE),
                )
            }
        }
    }
}

@Composable
private fun rememberGroupDragDropState(
    listState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
): GroupDragDropState {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)
    return remember(listState) {
        GroupDragDropState(
            listState = listState,
            onMove = { from, to -> currentOnMove(from, to) },
            onDragStateChanged = { dragging -> currentOnDragStateChanged(dragging) },
        )
    }
}

/** グループタブバーのドラッグ&ドロップ状態を管理するクラス */
@Stable
private class GroupDragDropState(
    val listState: LazyListState,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onDragStateChanged: (Boolean) -> Unit,
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
    private var currentDragIndex: Int = -1

    /** ドラッグ開始時のインデックス。目標位置はここからの累積移動量で決める。 */
    private var dragStartIndex: Int = -1

    /** ドラッグ開始からの水平移動量（px） */
    private var totalDragX: Float = 0f

    val isDragging: Boolean get() = draggedItemKey != null

    /** ドラッグ開始時の処理。groupCount は追加ボタンを除いたグループ数。 */
    fun onDragStart(offset: Offset, groupCount: Int) {
        val viewportOffset = listState.layoutInfo.viewportStartOffset
        val items = listState.layoutInfo.visibleItemsInfo.map {
            IndicatorItemInfo(it.index, it.offset, it.size)
        }
        val item = findGroupDragStartItem(
            items = items,
            offsetX = offset.x,
            viewportStartOffset = viewportOffset,
            groupCount = groupCount,
        ) ?: return

        draggedItemKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == item.index }
            ?.key
            ?: return
        draggedItemOffset = IntOffset(item.offset - viewportOffset, 0)
        draggedItemSize = IntSize(item.size, listState.layoutInfo.viewportSize.height)
        currentDragIndex = item.index
        dragStartIndex = item.index
        totalDragX = 0f
        onDragStateChanged(true)
    }

    /** ドラッグ中の移動処理 */
    fun onDrag(dragAmount: Offset, groupCount: Int) {
        if (!isDragging) return
        totalDragX += dragAmount.x
        draggedItemOffset = (draggedItemOffset.toOffset() + dragAmount).round()

        val targetIndex = calculateGroupReorderTargetIndex(
            startIndex = dragStartIndex,
            totalDragX = totalDragX,
            itemWidthPx = draggedItemSize.width.toFloat(),
            groupCount = groupCount,
            currentIndex = currentDragIndex,
        )
        if (targetIndex != currentDragIndex) {
            onMove(currentDragIndex, targetIndex)
            currentDragIndex = targetIndex
        }
    }

    /** ドラッグ終了時の処理 */
    fun onDragEnd() {
        val wasDragging = isDragging
        draggedItemKey = null
        draggedItemOffset = IntOffset.Zero
        draggedItemSize = IntSize.Zero
        currentDragIndex = -1
        dragStartIndex = -1
        totalDragX = 0f
        if (wasDragging) {
            onDragStateChanged(false)
        }
    }
}

/**
 * グループタブの長押しドラッグで、指の移動量から並び替え先インデックスを決める。
 *
 * LazyRow の layoutInfo は animateItem 中に中間座標を返すため、
 * 当たり判定に使うと隣アイテムと高速で入れ替わり続ける。
 * タブ幅は固定なので、開始インデックス + 移動量 / 幅 だけで目標を計算する。
 *
 * [currentIndex] からのヒステリシス（スロット幅の 55%）により、境界付近の微小な揺れで
 * 往復しない。素早いフリックで複数スロット飛ばした場合は追いつくまで進める。
 */
internal fun calculateGroupReorderTargetIndex(
    startIndex: Int,
    totalDragX: Float,
    itemWidthPx: Float,
    groupCount: Int,
    currentIndex: Int,
): Int {
    val lastIndex = (groupCount - 1).coerceAtLeast(0)
    if (itemWidthPx <= 0f || groupCount <= 0) {
        return startIndex.coerceIn(0, lastIndex)
    }
    val fingerIndex = startIndex + totalDragX / itemWidthPx
    val threshold = GroupReorderSlotThreshold
    var index = currentIndex.coerceIn(0, lastIndex)
    repeat(groupCount) {
        val delta = fingerIndex - index
        val next = when {
            delta > threshold && index < lastIndex -> index + 1
            delta < -threshold && index > 0 -> index - 1
            else -> index
        }
        if (next == index) return index
        index = next
    }
    return index
}

/**
 * 長押しドラッグ開始位置から対象グループタブを特定する。
 * @param groupCount 追加ボタンを除いたグループ数
 */
internal fun findGroupDragStartItem(
    items: List<IndicatorItemInfo>,
    offsetX: Float,
    viewportStartOffset: Int,
    groupCount: Int,
): IndicatorItemInfo? {
    // info.offset はコンテンツ領域先頭（contentPadding 以降）からの相対座標。
    // viewportStartOffset = -contentPadding.start のため引き算で描画座標に変換する。
    return items.firstOrNull { info ->
        val itemLeft = (info.offset - viewportStartOffset).toFloat()
        val itemRight = itemLeft + info.size
        info.index < groupCount && offsetX >= itemLeft && offsetX <= itemRight
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
    isPlaying: Boolean = false,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = "再生中",
                        modifier = Modifier.size(14.dp),
                        tint = if (isDropTarget) selectedTextColor else lerp(unselectedTextColor, selectedTextColor, fraction),
                    )
                }
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
                .testTag(TabsScreenTestTags.AddTabGroupButton.testTag)
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
                imageVector = Icons.Default.Add,
                contentDescription = "グループを追加",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

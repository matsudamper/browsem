package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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

/** ドラッグ中に自動スクロールを開始する、ビューポート端からの距離 */
private val AutoScrollThreshold = 48.dp

/** 自動スクロールの1フレームあたりの最大移動量 */
private val AutoScrollMaxSpeedPerFrame = 12.dp

/** 並び替え後にスクロール位置を上書きし直すフレーム数 */
private const val PinScrollFrameCount = 2

/**
 * グループタブバー。
 * 栞形のタブを横並びに表示し、末尾に追加ボタンを配置する。
 * 選択中のタブが手前に表示され、非選択タブは下に沈んで奥にあるように見える。
 * 長押しドラッグでグループの順序を入れ替えられる。
 * タブドラッグ中のドロップターゲットをハイライト表示する。
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
    onAddGroup: () -> Unit,
    onGroupTabBoundsChanged: (index: Int, bounds: Rect) -> Unit,
    onDraggingChanged: (isDragging: Boolean) -> Unit,
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

    // ドラッグ中はポインタイベントを長押しドラッグが消費するためユーザーはスクロールできない。
    // 端に近づいたら自動でスクロールし、画面外のグループまで移動できるようにする
    // ドラッグ中は画面側のタブバースクロール同期を止めてもらう（自動スクロールと競合するため）
    val currentOnDraggingChanged by rememberUpdatedState(onDraggingChanged)
    LaunchedEffect(dragDropState.isDragging) {
        currentOnDraggingChanged(dragDropState.isDragging)
    }

    val density = LocalDensity.current
    LaunchedEffect(dragDropState, dragDropState.isDragging) {
        if (!dragDropState.isDragging) return@LaunchedEffect
        val threshold = with(density) { AutoScrollThreshold.toPx() }
        val maxSpeed = with(density) { AutoScrollMaxSpeedPerFrame.toPx() }
        while (true) {
            // 直前の並び替えで LazyList がスクロール位置を補正していたら打ち消す
            dragDropState.pinScrollIfNeeded()
            val delta = dragDropState.autoScrollDelta(threshold = threshold, maxSpeed = maxSpeed)
            if (delta != 0f) {
                // scrollBy の戻り値は実際に消費された量。端に到達していれば 0 になる
                dragDropState.onAutoScrolled(listState.scrollBy(delta))
                // 指を止めたままでもスクロールで位置関係が変わるため、毎フレーム判定し直す
                dragDropState.updateDropTarget()
            }
            withFrameNanos { }
        }
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
): GroupDragDropState {
    return remember(listState) {
        GroupDragDropState(listState = listState, onMove = onMove)
    }
}

/** グループタブバーのドラッグ&ドロップ状態を管理するクラス */
@Stable
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

    /** グループタブ1つ分の幅（px）。スロット位置の算出に使う */
    private var itemWidth: Int = 0

    /** 追加ボタンを除いたグループ数 */
    private var groupCount: Int = 0

    /** ドラッグ開始時点の先頭からのスクロール量（px） */
    private var scrolledPxAtDragStart: Float = 0f

    /** ドラッグ開始以降に自動スクロールで実際に消費されたスクロール量（px） */
    private var autoScrolledPx: Float = 0f

    /**
     * 並び替え後にスクロール位置を上書きし直す残りフレーム数。
     * 並び替えの再レイアウトは次フレーム以降に走るため、1 フレームでは足りない。
     */
    private var pinScrollFrames: Int = 0

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
        itemWidth = item.size
        this.groupCount = groupCount
        scrolledPxAtDragStart = calculateScrolledPx(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            itemWidth = item.size,
            groupCount = groupCount,
        )
        autoScrolledPx = 0f
    }

    /** ドラッグ中の移動処理 */
    fun onDrag(dragAmount: Offset, groupCount: Int) {
        if (!isDragging) return
        this.groupCount = groupCount
        draggedItemOffset = (draggedItemOffset.toOffset() + dragAmount).round()
        updateDropTarget()
    }

    /**
     * 現在のドラッグ位置とスクロール位置から移動先を判定して並び替える。
     * 指を止めたまま自動スクロールした場合にも判定する必要があるため、
     * onDrag だけでなく自動スクロールのフレームごとにも呼ぶ。
     */
    fun updateDropTarget() {
        if (!isDragging || itemWidth <= 0) return
        val slots = calculateGroupSlots(
            groupCount = groupCount,
            itemWidth = itemWidth,
            startPadding = -listState.layoutInfo.viewportStartOffset.toFloat(),
            scrolledPx = scrolledPx(),
        )
        val centerX = draggedItemOffset.x + draggedItemSize.width / 2f
        val targetIndex = findGroupDropTargetIndex(
            slots = slots,
            centerX = centerX,
            currentIndex = currentDragIndex,
        ) ?: return

        onMove(currentDragIndex, targetIndex)
        currentDragIndex = targetIndex
        pinScrollFrames = PinScrollFrameCount
    }

    /**
     * 並び替えで LazyList がスクロール位置を補正した分を打ち消す。
     *
     * LazyList は並び替えが起きると先頭可視アイテムの「キー」を基準に表示位置を保つため、
     * そのアイテムの index が変わるとスクロール位置が 1 アイテム分ずれる。ドラッグ中の
     * スクロール位置はこちらで管理しているので、並び替えの直後は自分の値で上書きし直す。
     * これをしないと、ドラッグ中のタブが先頭可視アイテムを追い越すたびに実際の表示と
     * スロット位置の計算がずれ、末尾まで移動できなくなる。
     */
    suspend fun pinScrollIfNeeded() {
        if (!isDragging || itemWidth <= 0 || pinScrollFrames <= 0) return
        pinScrollFrames -= 1
        val position = calculateScrollToItemPosition(
            scrolledPx = scrolledPx(),
            itemWidth = itemWidth,
            groupCount = groupCount,
        )
        listState.scrollToItem(index = position.first, scrollOffset = position.second)
        // 末尾でクランプされた場合に追従できるよう、反映後の実際の位置を取り込む。
        // 明示的なスクロール直後は並び替えを挟んでいないため firstVisibleItem* を信頼できる
        autoScrolledPx = calculateScrolledPx(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            itemWidth = itemWidth,
            groupCount = groupCount,
        ) - scrolledPxAtDragStart
    }

    /** 自動スクロール量（px）。0 ならスクロール不要 */
    fun autoScrollDelta(threshold: Float, maxSpeed: Float): Float {
        if (!isDragging) return 0f
        return calculateAutoScrollDelta(
            draggedLeft = draggedItemOffset.x.toFloat(),
            draggedWidth = draggedItemSize.width,
            viewportWidth = listState.layoutInfo.viewportSize.width,
            threshold = threshold,
            maxSpeed = maxSpeed,
        )
    }

    /** 自動スクロールで実際に消費されたスクロール量を記録する */
    fun onAutoScrolled(consumed: Float) {
        autoScrolledPx += consumed
    }

    /** ドラッグ終了時の処理 */
    fun onDragEnd() {
        draggedItemKey = null
        draggedItemOffset = IntOffset.Zero
        draggedItemSize = IntSize.Zero
        currentDragIndex = -1
        itemWidth = 0
        groupCount = 0
        scrolledPxAtDragStart = 0f
        autoScrolledPx = 0f
        pinScrollFrames = 0
    }

    /**
     * 先頭からの現在のスクロール量（px）。
     *
     * ドラッグ中は firstVisibleItem* を読んではいけない。LazyList は並び替えが起きると
     * 先頭可視アイテムの「キー」を基準に表示位置を維持するため、そのアイテムの index が
     * 変わると firstVisibleItemIndex も追随して変わる。左方向へドラッグして先頭可視
     * アイテムを追い越すと index が 1 つ増え、スロット位置が 1 個分ずれて即座に戻され、
     * 往復（発振）していた。右方向は先頭可視アイテムから遠ざかるだけなので index が
     * 変わらず、発振しなかった。
     *
     * ドラッグ中のスクロールは自分の自動スクロールだけ（長押しドラッグがポインタ
     * イベントを消費するのでユーザーはスクロールできない）なので、開始時点の値に
     * 実際に消費されたスクロール量を積算すれば、並び順に一切影響されずに追跡できる。
     * LazyList 側が並び替えでスクロール位置を補正した分は pinScrollIfNeeded で打ち消す。
     */
    private fun scrolledPx(): Float = scrolledPxAtDragStart + autoScrolledPx
}

/**
 * 先頭からのスクロール量（px）を算出する。
 * グループタブは全て同じ幅なので index * itemWidth で先頭からの距離を求められる。
 */
internal fun calculateScrolledPx(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    itemWidth: Int,
    groupCount: Int,
): Float {
    val firstIndex = firstVisibleItemIndex.coerceIn(0, groupCount.coerceAtLeast(0))
    return firstIndex * itemWidth.toFloat() + firstVisibleItemScrollOffset
}

/** グループタブのスロット位置（ビューポート座標） */
internal data class GroupSlotInfo(val index: Int, val left: Float, val right: Float)

/**
 * 全グループタブのスロット位置を算出する。
 *
 * visibleItemsInfo の offset は animateItem の並び替えアニメーション中に変化するため、
 * それを見て判定すると「並び替え → アニメーションで動いた位置に反応 → 並び替え直し」を
 * 繰り返して振動する。グループタブは全て同じ幅で並び順によらずスロット位置は不変なので、
 * スクロール量から算出することで判定を安定させる。画面外のスロットも算出できるため、
 * 自動スクロールで端から端まで移動する場合にも対応できる。
 *
 * @param startPadding LazyRow の contentPadding.start（px）
 * @param scrolledPx 先頭からのスクロール量（px）
 */
internal fun calculateGroupSlots(
    groupCount: Int,
    itemWidth: Int,
    startPadding: Float,
    scrolledPx: Float,
): List<GroupSlotInfo> {
    if (groupCount <= 0 || itemWidth <= 0) return emptyList()
    return List(groupCount) { index ->
        val left = startPadding - scrolledPx + index * itemWidth
        GroupSlotInfo(index = index, left = left, right = left + itemWidth)
    }
}

/**
 * スクロール量（px）を LazyListState.scrollToItem に渡す (index, scrollOffset) に変換する。
 * calculateScrolledPx の逆変換にあたる。
 */
internal fun calculateScrollToItemPosition(
    scrolledPx: Float,
    itemWidth: Int,
    groupCount: Int,
): Pair<Int, Int> {
    if (itemWidth <= 0) return 0 to 0
    val clamped = scrolledPx.coerceAtLeast(0f)
    val index = (clamped / itemWidth).toInt().coerceIn(0, groupCount.coerceAtLeast(0))
    return index to (clamped - index * itemWidth).toInt().coerceAtLeast(0)
}

/**
 * ドラッグ中のアイテムがビューポート端に近づいた際の自動スクロール量を算出する。
 * 端に近いほど速くなり、しきい値の外では 0 を返す。
 *
 * @param draggedLeft ドラッグ中アイテムの左端（ビューポート座標）
 * @param threshold スクロールを開始する端からの距離（px）
 * @param maxSpeed 1回あたりの最大スクロール量（px）
 * @return 正: 末尾方向へスクロール / 負: 先頭方向へスクロール
 */
internal fun calculateAutoScrollDelta(
    draggedLeft: Float,
    draggedWidth: Int,
    viewportWidth: Int,
    threshold: Float,
    maxSpeed: Float,
): Float {
    if (viewportWidth <= 0 || threshold <= 0f) return 0f
    val draggedRight = draggedLeft + draggedWidth
    val endThreshold = viewportWidth - threshold
    return when {
        draggedLeft < threshold -> {
            -maxSpeed * ((threshold - draggedLeft) / threshold).coerceIn(0f, 1f)
        }

        draggedRight > endThreshold -> {
            maxSpeed * ((draggedRight - endThreshold) / threshold).coerceIn(0f, 1f)
        }

        else -> 0f
    }
}

/**
 * ドラッグ中のオーバーレイ中心位置から移動先スロットを求める。
 * 中心がどのスロットにも入らない場合（両端を越えた場合）は端のスロットへ寄せる。
 * @return 移動先インデックス。移動不要なら null
 */
internal fun findGroupDropTargetIndex(
    slots: List<GroupSlotInfo>,
    centerX: Float,
    currentIndex: Int,
): Int? {
    if (slots.isEmpty()) return null
    val sorted = slots.sortedBy { it.left }
    val target = when {
        centerX < sorted.first().left -> sorted.first()
        centerX > sorted.last().right -> sorted.last()
        else -> sorted.firstOrNull { centerX >= it.left && centerX <= it.right }
            // スロット間に隙間がある場合は最も近いスロットを選ぶ
            ?: sorted.minByOrNull { minOf(abs(centerX - it.left), abs(centerX - it.right)) }
    } ?: return null
    return target.index.takeIf { it != currentIndex }
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

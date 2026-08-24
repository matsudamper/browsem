package net.matsudamper.browser.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupTabBarDragTest {

    // GroupTabBar の contentPadding.start と同じ値（viewportStartOffset = -startPadding）
    private val viewportStartOffset = -8

    @Test
    fun findGroupDragStartItem_findsGroupWithinBounds() {
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = 120),
            IndicatorItemInfo(index = 1, offset = 120, size = 120),
        )
        val result = findGroupDragStartItem(
            items = items,
            offsetX = 60f,
            viewportStartOffset = viewportStartOffset,
            groupCount = 2,
        )
        assertEquals(0, result?.index)
    }

    @Test
    fun findGroupDragStartItem_excludesAddButtonIndex() {
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = 120),
            IndicatorItemInfo(index = 1, offset = 120, size = 56),
        )
        val result = findGroupDragStartItem(
            items = items,
            offsetX = 150f,
            viewportStartOffset = viewportStartOffset,
            groupCount = 1,
        )
        assertNull(result)
    }

    @Test
    fun findGroupDragStartItem_includesNewlyAddedGroupWhenGroupCountUpdated() {
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = 120),
            IndicatorItemInfo(index = 1, offset = 120, size = 120),
        )
        val result = findGroupDragStartItem(
            items = items,
            offsetX = 150f,
            viewportStartOffset = viewportStartOffset,
            groupCount = 2,
        )
        assertEquals(1, result?.index)
    }

    /** 5 グループ分のスロット（各幅 120px、スクロールなし） */
    private fun slots(count: Int = 5): List<GroupSlotInfo> {
        return List(count) { index ->
            GroupSlotInfo(
                index = index,
                left = index * 120f,
                right = index * 120f + 120f,
            )
        }
    }

    @Test
    fun findGroupDropTargetIndex_returnsNullWhileStayingInSameSlot() {
        // index 1 をつまんで少しだけ左に動かした状態（中心はまだスロット1内）
        val result = findGroupDropTargetIndex(
            slots = slots(),
            centerX = 130f,
            currentIndex = 1,
        )
        assertNull(result)
    }

    @Test
    fun findGroupDropTargetIndex_movesToPreviousSlot() {
        val result = findGroupDropTargetIndex(
            slots = slots(),
            centerX = 60f,
            currentIndex = 1,
        )
        assertEquals(0, result)
    }

    @Test
    fun findGroupDropTargetIndex_doesNotBounceBackAfterMove() {
        // 並び替え後（currentIndex = 0）も中心がスロット0内にある限り移動しない。
        // アニメーション中のアイテム位置を見て往復するデグレの回帰テスト
        val result = findGroupDropTargetIndex(
            slots = slots(),
            centerX = 60f,
            currentIndex = 0,
        )
        assertNull(result)
    }

    @Test
    fun findGroupDropTargetIndex_clampsToFirstSlotWhenDraggedBeforeStart() {
        val result = findGroupDropTargetIndex(
            slots = slots(),
            centerX = -200f,
            currentIndex = 2,
        )
        assertEquals(0, result)
    }

    @Test
    fun findGroupDropTargetIndex_clampsToLastSlotWhenDraggedBeyondEnd() {
        val result = findGroupDropTargetIndex(
            slots = slots(),
            centerX = 2000f,
            currentIndex = 2,
        )
        assertEquals(4, result)
    }

    @Test
    fun findGroupDropTargetIndex_returnsNullWhenNoSlots() {
        assertNull(
            findGroupDropTargetIndex(
                slots = emptyList(),
                centerX = 0f,
                currentIndex = 0,
            ),
        )
    }

    @Test
    fun calculateGroupSlots_placesSlotsAfterStartPadding() {
        val result = calculateGroupSlots(
            groupCount = 3,
            itemWidth = 120,
            startPadding = 8f,
            scrolledPx = 0f,
        )
        assertEquals(listOf(8f, 128f, 248f), result.map { it.left })
        assertEquals(listOf(128f, 248f, 368f), result.map { it.right })
    }

    @Test
    fun calculateGroupSlots_shiftsSlotsByScrollAmount() {
        // 自動スクロールで画面外へ出たスロットも算出できる
        val result = calculateGroupSlots(
            groupCount = 3,
            itemWidth = 120,
            startPadding = 8f,
            scrolledPx = 200f,
        )
        assertEquals(listOf(-192f, -72f, 48f), result.map { it.left })
    }

    @Test
    fun calculateGroupSlots_returnsEmptyWhenSizeUnknown() {
        assertEquals(
            emptyList<GroupSlotInfo>(),
            calculateGroupSlots(groupCount = 3, itemWidth = 0, startPadding = 8f, scrolledPx = 0f),
        )
        assertEquals(
            emptyList<GroupSlotInfo>(),
            calculateGroupSlots(groupCount = 0, itemWidth = 120, startPadding = 8f, scrolledPx = 0f),
        )
    }

    @Test
    fun calculateAutoScrollDelta_doesNotScrollInMiddle() {
        val delta = calculateAutoScrollDelta(
            draggedLeft = 300f,
            draggedWidth = 120,
            viewportWidth = 1000,
            threshold = 48f,
            maxSpeed = 12f,
        )
        assertEquals(0f, delta, 0f)
    }

    @Test
    fun calculateAutoScrollDelta_scrollsTowardStartNearLeftEdge() {
        // 左端に張り付いた状態では最大速度で先頭方向（負）へスクロールする
        val delta = calculateAutoScrollDelta(
            draggedLeft = 0f,
            draggedWidth = 120,
            viewportWidth = 1000,
            threshold = 48f,
            maxSpeed = 12f,
        )
        assertEquals(-12f, delta, 0f)
    }

    @Test
    fun calculateAutoScrollDelta_scrollsTowardEndNearRightEdge() {
        // 右端に張り付いた状態では最大速度で末尾方向（正）へスクロールする
        val delta = calculateAutoScrollDelta(
            draggedLeft = 880f,
            draggedWidth = 120,
            viewportWidth = 1000,
            threshold = 48f,
            maxSpeed = 12f,
        )
        assertEquals(12f, delta, 0f)
    }

    @Test
    fun calculateAutoScrollDelta_speedsUpTowardEdge() {
        // しきい値の半分まで近づいたら最大速度の半分
        val delta = calculateAutoScrollDelta(
            draggedLeft = 24f,
            draggedWidth = 120,
            viewportWidth = 1000,
            threshold = 48f,
            maxSpeed = 12f,
        )
        assertEquals(-6f, delta, 0.001f)
    }

    @Test
    fun calculateAutoScrollDelta_returnsZeroWhenViewportUnknown() {
        val delta = calculateAutoScrollDelta(
            draggedLeft = 0f,
            draggedWidth = 120,
            viewportWidth = 0,
            threshold = 48f,
            maxSpeed = 12f,
        )
        assertEquals(0f, delta, 0f)
    }

    @Test
    fun calculateScrolledPx_combinesIndexAndOffset() {
        val result = calculateScrolledPx(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 30,
            itemWidth = 120,
            groupCount = 5,
        )
        assertEquals(270f, result, 0f)
    }

    @Test
    fun calculateScrolledPx_clampsIndexToGroupCount() {
        // 追加ボタン（index == groupCount）が先頭可視でも、グループ分の幅までで頭打ちにする
        val result = calculateScrolledPx(
            firstVisibleItemIndex = 7,
            firstVisibleItemScrollOffset = 10,
            itemWidth = 120,
            groupCount = 5,
        )
        assertEquals(610f, result, 0f)
    }
}

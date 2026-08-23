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

    /** ドラッグ開始時に固定した 5 グループ分のスロット（各幅 120px） */
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
}

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

    @Test
    fun calculateGroupReorderTargetIndex_moveSecondToFirst() {
        val itemWidth = 120f
        val result = calculateGroupReorderTargetIndex(
            startIndex = 1,
            totalDragX = -itemWidth,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 1,
        )
        assertEquals(0, result)
    }

    @Test
    fun calculateGroupReorderTargetIndex_doesNotOscillateNearBoundary() {
        val itemWidth = 120f
        // 1つ分左へ動かした後、境界付近で数 px 揺れても元のスロットへ戻らない
        val afterMove = calculateGroupReorderTargetIndex(
            startIndex = 1,
            totalDragX = -itemWidth,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 0,
        )
        assertEquals(0, afterMove)

        val jitterRight = calculateGroupReorderTargetIndex(
            startIndex = 1,
            totalDragX = -itemWidth + 8f,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 0,
        )
        assertEquals(0, jitterRight)

        val jitterLeft = calculateGroupReorderTargetIndex(
            startIndex = 1,
            totalDragX = -itemWidth - 8f,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 0,
        )
        assertEquals(0, jitterLeft)
    }

    @Test
    fun calculateGroupReorderTargetIndex_returnsToOriginal_afterDraggingBackPastThreshold() {
        val itemWidth = 120f
        val result = calculateGroupReorderTargetIndex(
            startIndex = 1,
            totalDragX = -8f,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 0,
        )
        assertEquals(1, result)
    }

    @Test
    fun calculateGroupReorderTargetIndex_skipsMultipleSlotsOnFastDrag() {
        val itemWidth = 120f
        val result = calculateGroupReorderTargetIndex(
            startIndex = 0,
            totalDragX = itemWidth * 2.7f,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 0,
        )
        assertEquals(3, result)
    }

    @Test
    fun calculateGroupReorderTargetIndex_clampsToBounds() {
        val itemWidth = 120f
        val left = calculateGroupReorderTargetIndex(
            startIndex = 1,
            totalDragX = -itemWidth * 10f,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 1,
        )
        assertEquals(0, left)

        val right = calculateGroupReorderTargetIndex(
            startIndex = 3,
            totalDragX = itemWidth * 10f,
            itemWidthPx = itemWidth,
            groupCount = 5,
            currentIndex = 3,
        )
        assertEquals(4, right)
    }
}

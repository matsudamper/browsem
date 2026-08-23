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
}

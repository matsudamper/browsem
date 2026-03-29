package net.matsudamper.browser.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagerIndicatorTest {

    // GroupTabBar の Box に指定されている start padding と同じ値
    private val startOffsetPx = 8f

    @Test
    fun indicatorStartXMatchesTabOffset_whenNotScrolled() {
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = 300),
            IndicatorItemInfo(index = 1, offset = 300, size = 300),
        )
        val (startX, width) = requireNotNull(
            calculatePagerIndicatorBounds(
                items = items,
                currentPage = 0,
                offsetFraction = 0f,
                startOffsetPx = startOffsetPx,
            )
        )
        // startX = startOffsetPx + item.offset
        assertEquals(startOffsetPx + 0f, startX, 0.01f)
        assertEquals(300f, width, 0.01f)
    }

    @Test
    fun indicatorStartXMatchesTabVisualPosition_whenScrolled() {
        // タブバーが150pxスクロール済み: tab0 の viewport offset は -150px
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = -150, size = 300),
            IndicatorItemInfo(index = 1, offset = 150, size = 300),
        )
        val (startX, width) = requireNotNull(
            calculatePagerIndicatorBounds(
                items = items,
                currentPage = 0,
                offsetFraction = 0f,
                startOffsetPx = startOffsetPx,
            )
        )
        // スクロール後も startX はタブの実際の表示位置に追従する
        assertEquals(startOffsetPx + (-150f), startX, 0.01f)
        assertEquals(300f, width, 0.01f)
    }

    @Test
    fun indicatorInterpolatesBetweenTabs_duringSwipe() {
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = 300),
            IndicatorItemInfo(index = 1, offset = 300, size = 300),
        )
        val (startX, _) = requireNotNull(
            calculatePagerIndicatorBounds(
                items = items,
                currentPage = 0,
                offsetFraction = 0.5f,
                startOffsetPx = startOffsetPx,
            )
        )
        // 50% スワイプ: startOffsetPx + 0 + (300 - 0) * 0.5 = startOffsetPx + 150
        assertEquals(startOffsetPx + 150f, startX, 0.01f)
    }

    @Test
    fun indicatorInterpolatesBackward_duringReverseSwipe() {
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = 300),
            IndicatorItemInfo(index = 1, offset = 300, size = 300),
        )
        val (startX, _) = requireNotNull(
            calculatePagerIndicatorBounds(
                items = items,
                currentPage = 1,
                offsetFraction = -0.5f,
                startOffsetPx = startOffsetPx,
            )
        )
        // 50% 逆スワイプ: startOffsetPx + 300 + (0 - 300) * 0.5 = startOffsetPx + 150
        assertEquals(startOffsetPx + 150f, startX, 0.01f)
    }

    @Test
    fun indicatorIsNullWhenCurrentPageNotVisible() {
        // ページ0のタブが画面外でvisibleItemsInfoにない
        val items = listOf(
            IndicatorItemInfo(index = 1, offset = 300, size = 300),
        )
        val result = calculatePagerIndicatorBounds(
            items = items,
            currentPage = 0,
            offsetFraction = 0f,
            startOffsetPx = startOffsetPx,
        )
        assertNull(result)
    }

    @Test
    fun indicatorEndXMatchesTabEndOffset_whenNotScrolled() {
        val tabWidth = 300
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = 0, size = tabWidth),
            IndicatorItemInfo(index = 1, offset = tabWidth, size = tabWidth),
        )
        val (startX, width) = requireNotNull(
            calculatePagerIndicatorBounds(
                items = items,
                currentPage = 0,
                offsetFraction = 0f,
                startOffsetPx = startOffsetPx,
            )
        )
        // 終端 X = startX + width がタブの右端と一致する
        val endX = startX + width
        val tabEndX = startOffsetPx + 0f + tabWidth
        assertEquals(tabEndX, endX, 0.01f)
    }

    @Test
    fun indicatorEndXMatchesTabEndOffset_whenScrolled() {
        val tabWidth = 300
        val scrollOffset = 150
        // 150px スクロール済み: tab0 offset = -150, tab1 offset = 150
        val items = listOf(
            IndicatorItemInfo(index = 0, offset = -scrollOffset, size = tabWidth),
            IndicatorItemInfo(index = 1, offset = tabWidth - scrollOffset, size = tabWidth),
        )
        val (startX, width) = requireNotNull(
            calculatePagerIndicatorBounds(
                items = items,
                currentPage = 0,
                offsetFraction = 0f,
                startOffsetPx = startOffsetPx,
            )
        )
        val endX = startX + width
        val tabEndX = startOffsetPx + (-scrollOffset).toFloat() + tabWidth
        assertEquals(tabEndX, endX, 0.01f)
    }
}

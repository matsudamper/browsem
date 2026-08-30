package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.PanZoomController

class PullToRefreshPolicyTest {

    @Test
    fun `ページ最上部のスクロール可能ページでは発動できる`() {
        // 最上部では下方向のみスクロール可能で、縦オーバースクロールが許可される
        assertTrue(
            canTriggerPullToRefresh(
                handledResult = PanZoomController.INPUT_RESULT_HANDLED,
                scrollableDirections = PanZoomController.SCROLLABLE_FLAG_BOTTOM,
                overscrollDirections = PanZoomController.OVERSCROLL_FLAG_VERTICAL,
            ),
        )
    }

    @Test
    fun `スクロール不能な短いページでは発動できる`() {
        // 短いページは UNHANDLED + 方向なし + 縦横オーバースクロール可で返る
        assertTrue(
            canTriggerPullToRefresh(
                handledResult = PanZoomController.INPUT_RESULT_UNHANDLED,
                scrollableDirections = PanZoomController.SCROLLABLE_FLAG_NONE,
                overscrollDirections = PanZoomController.OVERSCROLL_FLAG_VERTICAL or
                    PanZoomController.OVERSCROLL_FLAG_HORIZONTAL,
            ),
        )
    }

    @Test
    fun `上方向にスクロールできる場合は発動できない`() {
        // ページ途中、またはピンチズーム中で visual viewport が上端より下にある場合
        assertFalse(
            canTriggerPullToRefresh(
                handledResult = PanZoomController.INPUT_RESULT_HANDLED,
                scrollableDirections = PanZoomController.SCROLLABLE_FLAG_TOP or
                    PanZoomController.SCROLLABLE_FLAG_BOTTOM,
                overscrollDirections = PanZoomController.OVERSCROLL_FLAG_VERTICAL,
            ),
        )
    }

    @Test
    fun `コンテンツがタッチを消費する場合は発動できない`() {
        // preventDefault / touch-action: none (X の画像ビューアー等の JS 管理ズーム)
        assertFalse(
            canTriggerPullToRefresh(
                handledResult = PanZoomController.INPUT_RESULT_HANDLED_CONTENT,
                scrollableDirections = PanZoomController.SCROLLABLE_FLAG_NONE,
                overscrollDirections = PanZoomController.OVERSCROLL_FLAG_VERTICAL,
            ),
        )
    }

    @Test
    fun `縦オーバースクロール不可の場合は発動できない`() {
        // overflow-y: hidden な body (ライトボックス表示中) や overscroll-behavior: none
        assertFalse(
            canTriggerPullToRefresh(
                handledResult = PanZoomController.INPUT_RESULT_UNHANDLED,
                scrollableDirections = PanZoomController.SCROLLABLE_FLAG_NONE,
                overscrollDirections = PanZoomController.OVERSCROLL_FLAG_NONE,
            ),
        )
    }

    @Test
    fun `IGNORED の場合は発動できない`() {
        // APZ が内部で消費しブラウザは何もすべきでないケース
        assertFalse(
            canTriggerPullToRefresh(
                handledResult = PanZoomController.INPUT_RESULT_IGNORED,
                scrollableDirections = PanZoomController.SCROLLABLE_FLAG_NONE,
                overscrollDirections = PanZoomController.OVERSCROLL_FLAG_VERTICAL,
            ),
        )
    }
}

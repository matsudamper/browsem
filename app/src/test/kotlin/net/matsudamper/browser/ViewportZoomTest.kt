package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportZoomTest {

    @Test
    fun viewportContentForHundredPercentUsesDeviceWidth() {
        assertEquals(
            "width=device-width,initial-scale=1",
            viewportContentForPageZoom(screenWidthDp = 400, percent = 100),
        )
    }

    @Test
    fun viewportContentForTwoHundredPercentHalvesViewportWidth() {
        assertEquals(
            "width=200,initial-scale=1",
            viewportContentForPageZoom(screenWidthDp = 400, percent = 200),
        )
    }

    @Test
    fun shouldReapplyPageZoomOnSpaNavigationWhenZoomed() {
        assertTrue(
            shouldReapplyPageZoomOnSpaLocationChange(
                pageZoomPercent = 200,
                isFullPageLoad = false,
            ),
        )
    }

    @Test
    fun shouldNotReapplyPageZoomOnSpaNavigationAtDefaultZoom() {
        assertFalse(
            shouldReapplyPageZoomOnSpaLocationChange(
                pageZoomPercent = 100,
                isFullPageLoad = false,
            ),
        )
    }

    @Test
    fun shouldNotReapplyPageZoomOnFullPageLoad() {
        assertFalse(
            shouldReapplyPageZoomOnSpaLocationChange(
                pageZoomPercent = 200,
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun injectionScriptContainsViewportContent() {
        val script = buildViewportZoomInjectionScript(
            viewportContent = "width=200,initial-scale=1",
            persistAcrossDomChanges = true,
        )
        assertTrue(script.startsWith("javascript:void("))
        assertTrue(script.contains("width=200,initial-scale=1"))
        assertTrue(script.contains("MutationObserver"))
    }

    @Test
    fun injectionScriptDisconnectsObserverWhenResettingZoom() {
        val script = buildViewportZoomInjectionScript(
            viewportContent = "width=device-width,initial-scale=1",
            persistAcrossDomChanges = false,
        )
        assertTrue(script.contains("disconnect()"))
        assertFalse(script.contains("MutationObserver"))
    }
}

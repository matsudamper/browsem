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
    fun shouldApplyPersistedPageZoomAfterRenderWhenSessionRestored() {
        assertTrue(
            shouldApplyPersistedPageZoomAfterRender(
                pageZoomPercent = 200,
                isFullPageLoadPending = false,
            ),
        )
    }

    @Test
    fun shouldNotApplyPersistedPageZoomAfterRenderDuringFullPageLoad() {
        assertFalse(
            shouldApplyPersistedPageZoomAfterRender(
                pageZoomPercent = 200,
                isFullPageLoadPending = true,
            ),
        )
    }

    @Test
    fun shouldNotApplyPersistedPageZoomAfterRenderAtDefaultZoom() {
        assertFalse(
            shouldApplyPersistedPageZoomAfterRender(
                pageZoomPercent = 100,
                isFullPageLoadPending = false,
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
        assertTrue(script.contains("new MutationObserver"))
    }

    @Test
    fun injectionScriptDisconnectsObserverWhenResettingZoom() {
        val script = buildViewportZoomInjectionScript(
            viewportContent = "width=device-width,initial-scale=1",
            persistAcrossDomChanges = false,
        )
        assertTrue(script.contains("disconnect()"))
        assertFalse(script.contains("new MutationObserver"))
        val nullIndex = script.indexOf("window.__browserViewportZoomContent=null")
        val applyCallIndex = script.indexOf("applyDirect(c);")
        assertTrue(
            "リセット時はグローバル状態を先にクリアする",
            nullIndex in 0 until applyCallIndex,
        )
    }

    @Test
    fun persistentInjectionScriptReadsCurrentGlobalZoomContent() {
        val script = buildViewportZoomInjectionScript(
            viewportContent = "width=200,initial-scale=1",
            persistAcrossDomChanges = true,
        )
        assertTrue(script.contains("var c=window.__browserViewportZoomContent"))
        assertTrue(script.contains("requestAnimationFrame(applyFromGlobal)"))
        assertTrue(script.contains("setTimeout(applyFromGlobal,0)"))
    }

    @Test
    fun injectionScriptUpdatesAllViewportMetaTags() {
        val script = buildViewportZoomInjectionScript(
            viewportContent = "width=200,initial-scale=1",
            persistAcrossDomChanges = true,
        )
        assertTrue(script.contains("querySelectorAll('meta[name=\"viewport\"]')"))
    }

    @Test
    fun persistentInjectionScriptSchedulesDelayedReapply() {
        val script = buildViewportZoomInjectionScript(
            viewportContent = "width=200,initial-scale=1",
            persistAcrossDomChanges = true,
        )
        assertTrue(script.contains("setTimeout(applyFromGlobal,300)"))
        assertTrue(script.contains("setTimeout(applyFromGlobal,500)"))
    }

    @Test
    fun persistentInjectionScriptUpdatesGlobalBeforeApplying() {
        val firstScript = buildViewportZoomInjectionScript(
            viewportContent = "width=200,initial-scale=1",
            persistAcrossDomChanges = true,
        )
        val secondScript = buildViewportZoomInjectionScript(
            viewportContent = "width=267,initial-scale=1",
            persistAcrossDomChanges = true,
        )
        assertTrue(firstScript.contains("window.__browserViewportZoomContent='width=200,initial-scale=1'"))
        assertTrue(secondScript.contains("window.__browserViewportZoomContent='width=267,initial-scale=1'"))
        assertTrue(secondScript.contains("applyFromGlobal()"))
    }
}

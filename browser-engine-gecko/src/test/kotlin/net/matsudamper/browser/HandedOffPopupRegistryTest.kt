package net.matsudamper.browser

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

class HandedOffPopupRegistryTest {

    @After
    fun tearDown() {
        HandedOffPopupRegistry.resetForTesting()
    }

    @Test
    fun `open される前の登録も opener を生存として扱う`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false

        HandedOffPopupRegistry.register(openerTabId = "opener", session = session)

        assertEquals(setOf("opener"), HandedOffPopupRegistry.liveOpenerTabIds())
    }

    @Test
    fun `open された後に閉じた登録は生存から外れる`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true
        HandedOffPopupRegistry.register(openerTabId = "opener", session = session)
        assertEquals(setOf("opener"), HandedOffPopupRegistry.liveOpenerTabIds())

        every { session.isOpen } returns false

        assertTrue(HandedOffPopupRegistry.liveOpenerTabIds().isEmpty())
    }

    @Test
    fun `unregister した登録は生存から外れる`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true
        HandedOffPopupRegistry.register(openerTabId = "opener", session = session)

        HandedOffPopupRegistry.unregister(session)

        assertTrue(HandedOffPopupRegistry.liveOpenerTabIds().isEmpty())
    }

    @Test
    fun `別画面へ渡した子がいる opener はタブ一覧に子が無くても retain する`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val openerSession = mockk<GeckoSession>(relaxed = true)
        val popupSession = mockk<GeckoSession>(relaxed = true)
        every { openerSession.isOpen } returns true
        every { popupSession.isOpen } returns true
        val opener = BrowserTab(
            tabId = "opener",
            session = openerSession,
            openerTabId = null,
            currentUrl = "",
            sessionState = "",
            title = "",
            previewBitmap = null,
        )
        HandedOffPopupRegistry.register(openerTabId = "opener", session = popupSession)

        BrowserSessionLifecycleController(runtime).retainOpenersOfLivePopups(
            tabs = listOf(opener),
            selectedTabId = null,
        )

        assertTrue(opener.retainForLivePopup)
        verify { openerSession.setPriorityHint(GeckoSession.PRIORITY_HIGH) }
    }

    @Test
    fun `渡した子が閉じた opener は retain を解除する`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val openerSession = mockk<GeckoSession>(relaxed = true)
        val popupSession = mockk<GeckoSession>(relaxed = true)
        every { openerSession.isOpen } returns true
        every { popupSession.isOpen } returns true
        val opener = BrowserTab(
            tabId = "opener",
            session = openerSession,
            openerTabId = null,
            currentUrl = "",
            sessionState = "",
            title = "",
            previewBitmap = null,
        )
        opener.retainForLivePopup = true
        HandedOffPopupRegistry.register(openerTabId = "opener", session = popupSession)
        HandedOffPopupRegistry.liveOpenerTabIds()
        every { popupSession.isOpen } returns false

        BrowserSessionLifecycleController(runtime).retainOpenersOfLivePopups(
            tabs = listOf(opener),
            selectedTabId = null,
        )

        assertFalse(opener.retainForLivePopup)
        verify { openerSession.setActive(false) }
    }
}

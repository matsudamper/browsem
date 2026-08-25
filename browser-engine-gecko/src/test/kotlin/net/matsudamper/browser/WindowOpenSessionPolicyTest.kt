package net.matsudamper.browser

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

class WindowOpenSessionPolicyTest {

    @Test
    fun `scheduleSelectAfterCallback は投稿前に select しない`() {
        val order = mutableListOf<String>()
        val posted = mutableListOf<() -> Unit>()

        WindowOpenSessionPolicy.scheduleSelectAfterCallback(
            postToMain = { action -> posted += action },
            postAfterFrame = { action -> action() },
            selectTab = { order += "select" },
            retainOpeners = { order += "retain" },
        )

        assertTrue(order.isEmpty())
        assertEquals(1, posted.size)
        posted.single().invoke()
        assertEquals(listOf("select", "retain"), order)
    }

    @Test
    fun `onNewSession 由来の子がいる opener は非表示でも setActive する`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val openerSession = mockk<GeckoSession>(relaxed = true)
        val childSession = mockk<GeckoSession>(relaxed = true)
        every { openerSession.isOpen } returns true
        every { childSession.isOpen } returns true
        val opener = createTab("opener", openerSession)
        val child = createTab("child", childSession, openerTabId = "opener")
        child.openedViaNewSession = true

        BrowserSessionLifecycleController(runtime).retainOpenersOfLivePopups(
            tabs = listOf(opener, child),
            selectedTabId = "child",
        )

        assertTrue(opener.retainForLivePopup)
        verify { openerSession.setActive(true) }
        verify { openerSession.setPriorityHint(GeckoSession.PRIORITY_HIGH) }
        verify(exactly = 0) { childSession.setPriorityHint(any()) }
    }

    @Test
    fun `子が居なくなった opener は非選択なら setActive false に戻す`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val openerSession = mockk<GeckoSession>(relaxed = true)
        every { openerSession.isOpen } returns true
        val opener = createTab("opener", openerSession)
        opener.retainForLivePopup = true

        BrowserSessionLifecycleController(runtime).retainOpenersOfLivePopups(
            tabs = listOf(opener),
            selectedTabId = "other",
        )

        assertFalse(opener.retainForLivePopup)
        verify { openerSession.setPriorityHint(GeckoSession.PRIORITY_DEFAULT) }
        verify { openerSession.setActive(false) }
    }

    @Test
    fun `retainForLivePopup の opener は pauseSession しない`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true
        val tab = createTab("opener", session)
        tab.retainForLivePopup = true

        BrowserSessionLifecycleController(runtime).pauseSession(tab)

        verify(exactly = 0) { session.setActive(false) }
    }

    @Test
    fun `コンテキストメニュー由来の openerTabId だけでは retain しない`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val openerSession = mockk<GeckoSession>(relaxed = true)
        val childSession = mockk<GeckoSession>(relaxed = true)
        every { openerSession.isOpen } returns true
        val opener = createTab("opener", openerSession)
        val child = createTab("child", childSession, openerTabId = "opener")
        child.openedViaNewSession = false

        BrowserSessionLifecycleController(runtime).retainOpenersOfLivePopups(
            tabs = listOf(opener, child),
            selectedTabId = "child",
        )

        assertFalse(opener.retainForLivePopup)
        verify(exactly = 0) { openerSession.setPriorityHint(any()) }
    }

    private fun createTab(
        tabId: String,
        session: GeckoSession,
        openerTabId: String? = null,
    ): BrowserTab {
        return BrowserTab(
            tabId = tabId,
            session = session,
            openerTabId = openerTabId,
            currentUrl = "",
            sessionState = "",
            title = "",
            previewBitmap = null,
        )
    }
}

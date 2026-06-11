package net.matsudamper.browser

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

class BrowserSessionLifecycleControllerTest {

    private fun createTab(session: GeckoSession): BrowserTab {
        return BrowserTab(
            tabId = "test-tab",
            session = session,
            openerTabId = null,
            currentUrl = "",
            sessionState = "",
            title = "",
            previewBitmap = null,
        )
    }

    /**
     * onNewSession 経由のタブは GeckoView が opener 連携付きで自動読み込みするため、
     * restoreSession が loadUri で上書きしてはいけない。
     * 上書きすると referrer / opener が失われ Google Pay などのポップアップ決済が壊れる。
     */
    @Test
    fun `オープン済みセッションは pendingInitialUrl があっても loadUri しない`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true
        val tab = createTab(session)
        tab.pendingInitialUrl = "https://pay.google.com/gp/p/ui/pay"

        BrowserSessionLifecycleController(runtime).restoreSession(tab)

        verify(exactly = 0) { session.loadUri(any<String>()) }
        verify { session.setActive(true) }
    }

    @Test
    fun `未オープンで pendingInitialUrl があるタブは GeckoView の自動読み込みを待つ`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false
        val tab = createTab(session)
        tab.pendingInitialUrl = "https://pay.google.com/gp/p/ui/pay"

        BrowserSessionLifecycleController(runtime).restoreSession(tab)

        verify(exactly = 0) { session.open(any()) }
        verify(exactly = 0) { session.loadUri(any<String>()) }
    }

    @Test
    fun `未オープンの通常タブは open して currentUrl を読み込む`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false
        val tab = createTab(session)
        tab.currentUrl = "https://example.com/"

        BrowserSessionLifecycleController(runtime).restoreSession(tab)

        verify { session.open(runtime) }
        verify { session.loadUri("https://example.com/") }
    }
}

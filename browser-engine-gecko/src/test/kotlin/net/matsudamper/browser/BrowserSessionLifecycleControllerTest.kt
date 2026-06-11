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

    /**
     * 初回ロードは GeckoView のサイズ確定後まで遅延する。
     * 未確定 viewport でロードすると ImageDocument の shrink-to-fit スケールが
     * 誤計算され、画像が小さく低解像度で表示されるため。
     */
    @Test
    fun `未オープンの通常タブは open するが初回ロードはサイズ確定まで遅延する`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false
        val tab = createTab(session)
        tab.currentUrl = "https://example.com/"
        val controller = BrowserSessionLifecycleController(runtime)

        controller.restoreSession(tab)

        verify { session.open(runtime) }
        verify(exactly = 0) { session.loadUri(any<String>()) }
        verify { session.setActive(true) }

        // サイズ確定後に performInitialLoadIfPending で currentUrl が読み込まれる
        every { session.isOpen } returns true
        controller.performInitialLoadIfPending(tab)

        verify { session.loadUri("https://example.com/") }
    }

    @Test
    fun `referrer 付きの遅延初回ロードは load(Loader) で読み込まれる`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false
        val tab = createTab(session)
        tab.currentUrl = "https://example.com/image.png"
        tab.pendingReferrerUrl = "https://example.com/page"
        val controller = BrowserSessionLifecycleController(runtime)

        controller.restoreSession(tab)
        every { session.isOpen } returns true
        controller.performInitialLoadIfPending(tab)

        verify { session.load(any()) }
        verify(exactly = 0) { session.loadUri(any<String>()) }
    }

    @Test
    fun `初回ロードは一度しか実行されない`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false
        val tab = createTab(session)
        tab.currentUrl = "https://example.com/"
        val controller = BrowserSessionLifecycleController(runtime)

        controller.restoreSession(tab)
        every { session.isOpen } returns true
        controller.performInitialLoadIfPending(tab)
        controller.performInitialLoadIfPending(tab)

        verify(exactly = 1) { session.loadUri("https://example.com/") }
    }

    @Test
    fun `サイズ確定待ち中にタブが閉じられた場合はロードしない`() {
        val runtime = mockk<GeckoRuntime>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns false
        val tab = createTab(session)
        tab.currentUrl = "https://example.com/"
        val controller = BrowserSessionLifecycleController(runtime)

        controller.restoreSession(tab)
        // session.isOpen が false のまま（閉じられた状態）
        controller.performInitialLoadIfPending(tab)

        verify(exactly = 0) { session.loadUri(any<String>()) }
        verify(exactly = 0) { session.load(any()) }
    }
}

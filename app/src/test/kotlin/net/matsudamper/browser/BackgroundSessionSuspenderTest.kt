package net.matsudamper.browser

import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.matsudamper.browser.feature.media.MediaWebExtension
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundSessionSuspenderTest {

    @Test
    fun `画面外では再生中でないタブだけ止め、戻ったら active に戻す`() {
        val mediaWebExtension = mockk<MediaWebExtension>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        val playingSession = mockk<GeckoSession>(relaxed = true)
        val pausedTab = createTab("paused", session)
        val playingTab = createTab("playing", playingSession)
        val lifecycleController = mockk<BrowserSessionLifecycleController>(relaxed = true)
        every { mediaWebExtension.isSessionPlaying(session) } returns false
        every { mediaWebExtension.isSessionPlaying(playingSession) } returns true
        every { lifecycleController.suspendWhileHostStopped(pausedTab, false) } returns true
        every { lifecycleController.suspendWhileHostStopped(playingTab, true) } returns false
        val suspender = BackgroundSessionSuspender(mediaWebExtension)
        val owner = mockk<LifecycleOwner>()
        suspender.track(
            BackgroundSessionHost(
                tabs = { listOf(pausedTab, playingTab) },
                lifecycleController = lifecycleController,
            ),
        )

        suspender.onStop(owner)
        suspender.onStart(owner)

        verify { mediaWebExtension.onHostEnteredBackground() }
        verify { lifecycleController.suspendWhileHostStopped(pausedTab, false) }
        verify { lifecycleController.suspendWhileHostStopped(playingTab, true) }
        verify { mediaWebExtension.onHostEnteredForeground() }
        verify { lifecycleController.resumeSuspendedWhileHostStopped(pausedTab) }
        verify(exactly = 0) { lifecycleController.resumeSuspendedWhileHostStopped(playingTab) }
    }

    @Test
    fun `登録を外したタブは前面に戻しても active に戻さない`() {
        val mediaWebExtension = mockk<MediaWebExtension>(relaxed = true)
        val session = mockk<GeckoSession>(relaxed = true)
        val tab = createTab("paused", session)
        val lifecycleController = mockk<BrowserSessionLifecycleController>(relaxed = true)
        every { mediaWebExtension.isSessionPlaying(session) } returns false
        every { lifecycleController.suspendWhileHostStopped(tab, false) } returns true
        val suspender = BackgroundSessionSuspender(mediaWebExtension)
        val host = BackgroundSessionHost(
            tabs = { listOf(tab) },
            lifecycleController = lifecycleController,
        )
        val owner = mockk<LifecycleOwner>()
        suspender.track(host)
        suspender.onStop(owner)
        suspender.untrack(host)

        suspender.onStart(owner)

        verify(exactly = 0) { lifecycleController.resumeSuspendedWhileHostStopped(any()) }
    }

    private fun createTab(tabId: String, session: GeckoSession): BrowserTab {
        return BrowserTab(
            tabId = tabId,
            session = session,
            openerTabId = null,
            currentUrl = "",
            sessionState = "",
            title = "",
            previewBitmap = null,
        )
    }
}

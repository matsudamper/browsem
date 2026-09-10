package net.matsudamper.browser

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner

// GeckoResult は Looper を必要とするため Robolectric 上で実行する
@RunWith(RobolectricTestRunner::class)
class WindowOpenHandoffHoldingDelegateTest {

    @Test
    fun `タブへ載せる前の window_close を覚える`() {
        val session = mockk<GeckoSession>(relaxed = true)
        val delegate = WindowOpenHandoffHoldingDelegate(onChainedWindowOpen = { mockk(relaxed = true) })

        assertFalse(delegate.isCloseRequested)
        delegate.onCloseRequest(session)

        assertTrue(delegate.isCloseRequested)
    }

    @Test
    fun `タブへ載せる前の window_open は次のカスタムタブへ渡す`() {
        val session = mockk<GeckoSession>(relaxed = true)
        val chainedSession = mockk<GeckoSession>(relaxed = true)
        val requestedUris = mutableListOf<String>()
        val delegate = WindowOpenHandoffHoldingDelegate(
            onChainedWindowOpen = { uri ->
                requestedUris += uri
                chainedSession
            },
        )

        val result = delegate.onNewSession(session, "https://example.com/child")

        assertSame(chainedSession, result.poll(0))
        assertTrue(requestedUris == listOf("https://example.com/child"))
    }
}

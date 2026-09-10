package net.matsudamper.browser

import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mozilla.geckoview.GeckoSession

class WindowOpenHandoffStoreTest {

    @After
    fun tearDown() {
        WindowOpenHandoffStore.resetForTesting()
        HandedOffPopupRegistry.resetForTesting()
    }

    @Test
    fun `預けたセッションをトークンで取り出せる`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true

        val token = WindowOpenHandoffStore.store(
            session = session,
            initialUrl = "https://example.com/popup",
            openerTabId = "opener",
        )
        val handoff = WindowOpenHandoffStore.consume(token)

        assertNotNull(handoff)
        assertSame(session, handoff?.session)
        assertEquals("https://example.com/popup", handoff?.initialUrl)
    }

    @Test
    fun `取り出したトークンは再利用できない`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true
        val token = WindowOpenHandoffStore.store(
            session = session,
            initialUrl = "https://example.com/popup",
            openerTabId = "opener",
        )

        WindowOpenHandoffStore.consume(token)

        assertNull(WindowOpenHandoffStore.consume(token))
    }

    @Test
    fun `預けるたびに異なるトークンを発行する`() {
        val first = mockk<GeckoSession>(relaxed = true)
        val second = mockk<GeckoSession>(relaxed = true)
        every { first.isOpen } returns true
        every { second.isOpen } returns true

        val firstToken = WindowOpenHandoffStore.store(
            session = first,
            initialUrl = "https://example.com/1",
            openerTabId = "opener",
        )
        val secondToken = WindowOpenHandoffStore.store(
            session = second,
            initialUrl = "https://example.com/2",
            openerTabId = "opener",
        )

        assertNotEquals(firstToken, secondToken)
        assertSame(first, WindowOpenHandoffStore.consume(firstToken)?.session)
        assertSame(second, WindowOpenHandoffStore.consume(secondToken)?.session)
    }

    @Test
    fun `預けたセッションは opener の生存判定に登録される`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true

        WindowOpenHandoffStore.store(
            session = session,
            initialUrl = "https://example.com/popup",
            openerTabId = "opener",
        )

        assertEquals(setOf("opener"), HandedOffPopupRegistry.liveOpenerTabIds())
    }
}

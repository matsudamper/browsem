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
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner

// 期限切れの掃除を Handler で予約するため Robolectric 上で実行する
@RunWith(RobolectricTestRunner::class)
class CustomTabHandoffStoreTest {

    @After
    fun tearDown() {
        CustomTabHandoffStore.resetForTesting()
    }

    @Test
    fun `預けたセッションをトークンで取り出せる`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true

        val token = CustomTabHandoffStore.store(session = session, sessionState = "state-A", onDiscard = {})
        val handoff = CustomTabHandoffStore.consume(token)

        assertNotNull(handoff)
        assertSame(session, handoff?.session)
        assertEquals("state-A", handoff?.sessionState)
    }

    @Test
    fun `取り出したトークンは再利用できない`() {
        val session = mockk<GeckoSession>(relaxed = true)
        every { session.isOpen } returns true
        val token = CustomTabHandoffStore.store(session = session, sessionState = "state-A", onDiscard = {})

        CustomTabHandoffStore.consume(token)

        assertNull(CustomTabHandoffStore.consume(token))
    }

    @Test
    fun `複数カスタムタブは独立したトークンを持ち取り出し順に依存しない`() {
        val first = mockk<GeckoSession>(relaxed = true)
        val second = mockk<GeckoSession>(relaxed = true)
        every { first.isOpen } returns true
        every { second.isOpen } returns true

        val firstToken = CustomTabHandoffStore.store(session = first, sessionState = "state-A", onDiscard = {})
        val secondToken = CustomTabHandoffStore.store(session = second, sessionState = "state-B", onDiscard = {})

        assertNotEquals(firstToken, secondToken)
        assertSame(second, CustomTabHandoffStore.consume(secondToken)?.session)
        assertSame(first, CustomTabHandoffStore.consume(firstToken)?.session)
    }

    @Test
    fun `上限超過で破棄されたセッションは onDiscard を受け取る`() {
        val discarded = mutableListOf<GeckoSession>()
        val sessions = List(MAX_ENTRIES + 1) { index ->
            mockk<GeckoSession>(relaxed = true).also { session ->
                every { session.isOpen } returns true
                CustomTabHandoffStore.store(
                    session = session,
                    sessionState = "state-$index",
                    onDiscard = { discarded += it },
                )
            }
        }

        assertEquals(listOf(sessions.first()), discarded)
    }

    @Test
    fun `未知のトークンは null`() {
        assertNull(CustomTabHandoffStore.consume("does-not-exist"))
    }

    private companion object {
        // CustomTabHandoffStore.MAX_ENTRIES と同じ値
        private const val MAX_ENTRIES = 8
    }
}

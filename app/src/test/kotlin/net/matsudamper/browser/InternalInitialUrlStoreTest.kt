package net.matsudamper.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternalInitialUrlStoreTest {

    @After
    fun tearDown() {
        InternalInitialUrlStore.resetForTesting()
    }

    @Test
    fun `登録した URL をトークンで取り出せる`() {
        val token = InternalInitialUrlStore.store("moz-extension://uuid/options.html")
        assertEquals("moz-extension://uuid/options.html", InternalInitialUrlStore.consume(token))
    }

    @Test
    fun `一度取り出したトークンは無効になる`() {
        val token = InternalInitialUrlStore.store("moz-extension://uuid/options.html")
        InternalInitialUrlStore.consume(token)
        assertNull(InternalInitialUrlStore.consume(token))
    }

    @Test
    fun `未登録のトークンは null を返す`() {
        assertNull(InternalInitialUrlStore.consume("unknown-token"))
    }

    @Test
    fun `トークンは登録ごとに異なる`() {
        val first = InternalInitialUrlStore.store("moz-extension://uuid/options.html")
        val second = InternalInitialUrlStore.store("moz-extension://uuid/options.html")
        assertEquals(setOf(first, second).size, 2)
    }
}

package net.matsudamper.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomTabHandoffStoreTest {

    @After
    fun tearDown() {
        CustomTabHandoffStore.resetForTesting()
    }

    @Test
    fun `store して consume すると同じ状態が返る`() {
        val token = CustomTabHandoffStore.store("state-A")
        assertEquals("state-A", CustomTabHandoffStore.consume(token))
    }

    @Test
    fun `consume すると削除され二度目は null`() {
        val token = CustomTabHandoffStore.store("state-A")
        CustomTabHandoffStore.consume(token)
        assertNull(CustomTabHandoffStore.consume(token))
    }

    @Test
    fun `複数カスタムタブは独立したトークンを持ち取り出し順に依存しない`() {
        val tokenA = CustomTabHandoffStore.store("state-A")
        val tokenB = CustomTabHandoffStore.store("state-B")

        assertNotEquals(tokenA, tokenB)
        assertEquals("state-B", CustomTabHandoffStore.consume(tokenB))
        assertEquals("state-A", CustomTabHandoffStore.consume(tokenA))
    }

    @Test
    fun `未知のトークンは null`() {
        assertNull(CustomTabHandoffStore.consume("does-not-exist"))
    }
}

package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateSerializationTest {
    @Test
    fun `上限以内の状態は加工せず返す`() {
        val state = "a".repeat(MAX_SESSION_STATE_CHARS)
        assertEquals(state, serializeSessionState({ state }, { error("保存可能な状態です: $it") }))
    }

    @Test
    fun `上限超過は保存せず文字数を報告する`() {
        val reasons = mutableListOf<String>()
        val state = "a".repeat(MAX_SESSION_STATE_CHARS + 1)
        assertNull(serializeSessionState({ state }, reasons::add))
        assertTrue(reasons.single().contains("chars=${state.length}"))
        assertTrue(reasons.single().contains("reason=size_limit"))
    }

    @Test
    fun `OOM は呼び出し元に伝播せず理由を報告する`() {
        val reasons = mutableListOf<String>()
        assertNull(serializeSessionState({ throw OutOfMemoryError("テスト用") }, reasons::add))
        assertEquals(listOf("reason=OutOfMemoryError, chars=unknown"), reasons)
    }

    @Test
    fun `実行時例外は呼び出し元に伝播せず理由を報告する`() {
        val reasons = mutableListOf<String>()
        assertNull(serializeSessionState({ throw IllegalStateException("テスト用") }, reasons::add))
        assertEquals(listOf("reason=IllegalStateException, chars=unknown"), reasons)
    }

    @Test
    fun `空の状態は以前の保存値を消す値として返さない`() {
        for (state in listOf(null, "", " ")) {
            val reasons = mutableListOf<String>()
            assertNull(serializeSessionState({ state }, reasons::add))
            assertTrue(reasons.single().contains("reason=empty"))
        }
    }
}

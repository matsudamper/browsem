package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionStateSerializationTest {
    @Test
    fun `状態は加工せず返す`() {
        val state = "{\"history\":[]}"
        assertEquals(state, serializeSessionState({ state }, { error("保存可能な状態です: $it") }))
    }

    @Test
    fun `OOM は保存済み履歴を削除する空文字と理由を返す`() {
        val reasons = mutableListOf<String>()
        assertEquals("", serializeSessionState({ throw OutOfMemoryError("テスト用") }, reasons::add))
        assertEquals(listOf("reason=OutOfMemoryError, chars=unknown"), reasons)
    }

    @Test
    fun `実行時例外は呼び出し元に伝播する`() {
        val failure = IllegalStateException("テスト用")
        val thrown = assertThrows(IllegalStateException::class.java) {
            serializeSessionState({ throw failure }, { error("OOM 以外の理由は通知しません: $it") })
        }
        assertSame(failure, thrown)
    }

    @Test
    fun `null は空文字として返す`() {
        assertEquals("", serializeSessionState({ null }, { error("null は通知しません: $it") }))
    }

    @Test
    fun `空文字と空白文字列はそのまま返す`() {
        for (state in listOf("", " ")) {
            assertEquals(state, serializeSessionState({ state }, { error("空の状態は通知しません: $it") }))
        }
    }
}

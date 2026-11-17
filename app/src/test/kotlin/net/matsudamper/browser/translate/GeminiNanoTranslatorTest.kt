package net.matsudamper.browser.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNanoTranslatorTest {
    @Test
    fun 文字を含まないテキストは翻訳対象にしない() {
        assertFalse(isTranslatableText("123 - 456"))
        assertFalse(isTranslatableText("   "))
        assertTrue(isTranslatableText("2024年"))
        assertTrue(isTranslatableText("Hello"))
    }
}

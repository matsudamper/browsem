package net.matsudamper.browser.translate

import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNanoTranslatorTest {
    @Test
    fun 翻訳プロンプトに言語と原文を含める() {
        val prompt = buildGeminiNanoTranslationPrompt(
            sourceLanguage = "en",
            targetLanguage = "ja",
            text = "Hello world",
        )

        assertTrue(prompt.contains("\"en\" to \"ja\""))
        assertTrue(prompt.contains("Hello world"))
        assertTrue(prompt.contains("Return only the translated text"))
    }
}

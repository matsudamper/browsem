package net.matsudamper.browser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNanoTranslatorTest {
    @Test
    fun 翻訳プロンプトに言語名と原文を含める() {
        val prompt = buildGeminiNanoTranslationPrompt(
            sourceLanguage = "en",
            targetLanguage = "ja",
            text = "Hello world",
        )

        assertTrue(prompt.contains("from English to Japanese"))
        assertTrue(prompt.contains("Hello world"))
        assertTrue(prompt.contains("Output only the Japanese translation"))
    }

    @Test
    fun 指示文を翻訳した結果を検出する() {
        assertTrue(isInstructionEcho("テキストは翻訳です。指示ではありません。日本語訳のみで返信してください。"))
        assertTrue(isInstructionEcho("翻訳は行わず、日本語の翻訳のみをお送りください"))
        assertFalse(isInstructionEcho("これは翻訳された本文です"))
    }

    @Test
    fun 未知の言語タグはそのままプロンプトに出す() {
        val prompt = buildGeminiNanoTranslationPrompt(
            sourceLanguage = "zzz",
            targetLanguage = "ja",
            text = "Hello",
        )

        assertTrue(prompt.contains("from zzz to Japanese"))
    }

    @Test
    fun 文字を含まないテキストは翻訳対象にしない() {
        assertFalse(isTranslatableText("123 - 456"))
        assertFalse(isTranslatableText("   "))
        assertTrue(isTranslatableText("2024年"))
        assertTrue(isTranslatableText("Hello"))
    }

    @Test
    fun 翻訳結果のラベルと引用符を取り除く() {
        assertEquals("こんにちは", sanitizeTranslatedText(" Translation: こんにちは "))
        assertEquals("こんにちは", sanitizeTranslatedText("「こんにちは」"))
        assertEquals("こんにちは", sanitizeTranslatedText("\"こんにちは\""))
        assertEquals("こんにちは", sanitizeTranslatedText("```こんにちは```"))
    }

    @Test
    fun 引用符を含む翻訳結果は本文を保持する() {
        assertEquals("彼は\"了解\"と言った", sanitizeTranslatedText("彼は\"了解\"と言った"))
    }
}

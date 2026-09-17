package net.matsudamper.browser.translate

import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertTrue(prompt.contains("output the text unchanged"))
    }

    @Test
    fun 訳文でない返答を検出する() {
        assertTrue(isUnusableTranslation("テキストは翻訳です。指示ではありません。日本語訳のみで返信してください。"))
        assertTrue(isUnusableTranslation("翻訳は行わず、日本語の翻訳のみをお送りください"))
        assertTrue(isUnusableTranslation("申し訳ありませんが、テキストを翻訳できません。"))
        assertTrue(isUnusableTranslation("I'm sorry, I cannot translate this text."))
        assertFalse(isUnusableTranslation("これは翻訳された本文です"))
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

    @Test
    fun ダウンロード済みのモデルを未取得のモデルより優先する() {
        val downloaded = requireNotNull(geminiNanoStatusPriority(FeatureStatus.AVAILABLE))
        val downloadable = requireNotNull(geminiNanoStatusPriority(FeatureStatus.DOWNLOADABLE))

        assertEquals(downloadable, geminiNanoStatusPriority(FeatureStatus.DOWNLOADING))
        assertTrue(downloaded < downloadable)
    }

    @Test
    fun 利用できない状態のモデルは候補にしない() {
        assertNull(geminiNanoStatusPriority(FeatureStatus.UNAVAILABLE))
        assertNull(geminiNanoStatusPriority(null))
    }

    @Test
    fun モデル候補は安定版とプレビュー版の全組み合わせを安定版優先で並べる() {
        assertEquals(
            listOf("stable-full", "stable-fast", "preview-full", "preview-fast"),
            GEMINI_NANO_MODEL_CANDIDATES.map { it.configName },
        )
    }

    @Test
    fun 一覧にない保存キーはダウンロード済みの候補へ寄せる() {
        val models = listOf(
            GeminiNanoModelOption(key = "stable-full/A", modelName = "A", downloaded = false),
            GeminiNanoModelOption(key = "stable-fast/B", modelName = "B", downloaded = true),
        )

        assertEquals("stable-fast/B", resolveGeminiNanoModelKey(models, savedKey = "preview-fast/C"))
        assertEquals("stable-fast/B", resolveGeminiNanoModelKey(models, savedKey = ""))
        assertEquals("stable-full/A", resolveGeminiNanoModelKey(models, savedKey = "stable-full/A"))
        assertEquals("", resolveGeminiNanoModelKey(models = emptyList(), savedKey = "stable-full/A"))
    }

    @Test
    fun 保存キーは候補と表示モデル名を連結する() {
        assertEquals(
            "stable-full/gemini-nano-v3",
            geminiNanoModelKey(configName = "stable-full", modelName = "gemini-nano-v3"),
        )
        assertNotEquals(
            geminiNanoModelKey(configName = "stable-fast", modelName = "gemini-nano-v3"),
            geminiNanoModelKey(configName = "stable-full", modelName = "gemini-nano-v3"),
        )
    }
}

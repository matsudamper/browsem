package net.matsudamper.browser.translate

import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNanoModelTest {
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
    fun 表示名から実行環境の注記を落とす() {
        assertEquals("Gemini Nano 4 Fast", toGeminiNanoModelLabel("Gemini Nano 4 Fast [Preview, CPU]"))
        assertEquals("Gemini Nano 3 Full", toGeminiNanoModelLabel("Gemini Nano 3 Full"))
        assertEquals("[Preview]", toGeminiNanoModelLabel("[Preview]"))
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

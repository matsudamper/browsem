package net.matsudamper.browser.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAITranslatorTest {
    @Test
    fun 言語検出サンプルは指定文字数で打ち切る() {
        val segments = listOf(
            PageTranslationWebExtension.Segment(id = "1", text = "Hello"),
            PageTranslationWebExtension.Segment(id = "2", text = "world"),
        )

        assertEquals("Hello\nwor", buildLanguageDetectionSample(segments, 9))
    }

    @Test
    fun 言語検出サンプルは要素間を改行で区切る() {
        val segments = listOf(
            PageTranslationWebExtension.Segment(id = "1", text = "First sentence"),
            PageTranslationWebExtension.Segment(id = "2", text = "Second sentence"),
        )

        assertEquals(
            "First sentence\nSecond sentence",
            buildLanguageDetectionSample(segments, 100),
        )
    }

    @Test
    fun 言語検出上限がゼロなら空文字を返す() {
        val segments = listOf(PageTranslationWebExtension.Segment(id = "1", text = "Hello"))

        assertEquals("", buildLanguageDetectionSample(segments, 0))
    }
}

package net.matsudamper.browser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiNanoBatchTranslationTest {
    @Test
    fun まとめ翻訳を使わないときは1件ずつのグループになる() {
        val segments = segments("a", "b", "c")

        val groups = groupSegmentsForBatchTranslation(segments, batchTranslationEnabled = false)

        assertEquals(segments.map { listOf(it) }, groups)
    }

    @Test
    fun 件数上限までまとめる() {
        val segments = (1..10).map { PageTranslationWebExtension.Segment(id = "t$it", text = "text $it") }

        val groups = groupSegmentsForBatchTranslation(segments, batchTranslationEnabled = true)

        assertEquals(listOf(BATCH_TRANSLATION_SEGMENT_LIMIT, 2), groups.map { it.size })
        assertEquals(segments, groups.flatten())
    }

    @Test
    fun 合計文字数の上限を超えるところで区切る() {
        val half = "a".repeat(BATCH_TRANSLATION_CHAR_LIMIT / 2)
        val segments = segments(half, half, "b")

        val groups = groupSegmentsForBatchTranslation(segments, batchTranslationEnabled = true)

        assertEquals(listOf(2, 1), groups.map { it.size })
    }

    @Test
    fun 改行を含む文や長文は単独グループにする() {
        val long = "a".repeat(BATCH_TRANSLATION_CHAR_LIMIT + 1)
        val segments = segments("x", "line1\nline2", "y", long, "z")

        val groups = groupSegmentsForBatchTranslation(segments, batchTranslationEnabled = true)

        assertEquals(
            listOf(listOf("x"), listOf("line1\nline2"), listOf("y"), listOf(long), listOf("z")),
            groups.map { group -> group.map { it.text } },
        )
    }

    @Test
    fun 入力は1始まりの番号付きで並べる() {
        assertEquals("1: Hello\n2: World", buildGeminiNanoBatchTranslationInput(listOf("Hello", "World")))
    }

    @Test
    fun 出力を入力順に並べ直す() {
        val ordered = orderBatchTranslations(
            sourceCount = 2,
            translations = listOf(
                GeminiNanoTranslatedSegment(index = 2, text = "世界"),
                GeminiNanoTranslatedSegment(index = 1, text = "\"こんにちは\""),
            ),
        )

        assertEquals(listOf("こんにちは", "世界"), ordered)
    }

    @Test
    fun 件数や番号が合わなければ採用しない() {
        assertNull(
            orderBatchTranslations(
                sourceCount = 2,
                translations = listOf(GeminiNanoTranslatedSegment(index = 1, text = "a")),
            ),
        )
        assertNull(
            orderBatchTranslations(
                sourceCount = 2,
                translations = listOf(
                    GeminiNanoTranslatedSegment(index = 1, text = "a"),
                    GeminiNanoTranslatedSegment(index = 1, text = "b"),
                ),
            ),
        )
        assertNull(
            orderBatchTranslations(
                sourceCount = 2,
                translations = listOf(
                    GeminiNanoTranslatedSegment(index = 0, text = "a"),
                    GeminiNanoTranslatedSegment(index = 2, text = "b"),
                ),
            ),
        )
    }

    @Test
    fun 訳文として使えないエントリがあれば採用しない() {
        assertNull(
            orderBatchTranslations(
                sourceCount = 2,
                translations = listOf(
                    GeminiNanoTranslatedSegment(index = 1, text = "こんにちは"),
                    GeminiNanoTranslatedSegment(index = 2, text = "申し訳ありませんが、翻訳できません。"),
                ),
            ),
        )
        assertNull(
            orderBatchTranslations(
                sourceCount = 1,
                translations = listOf(GeminiNanoTranslatedSegment(index = 1, text = "   ")),
            ),
        )
    }

    private fun segments(vararg texts: String): List<PageTranslationWebExtension.Segment> =
        texts.mapIndexed { index, text -> PageTranslationWebExtension.Segment(id = "t$index", text = text) }
}

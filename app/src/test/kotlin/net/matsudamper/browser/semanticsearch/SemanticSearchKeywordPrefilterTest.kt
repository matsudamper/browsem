package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchKeywordPrefilterTest {
    @Test
    fun prefilterPrefersSegmentsContainingQueryTerms() {
        val segments = listOf(
            PageTranslationWebExtension.Segment(id = "t1", text = "今日の天気は晴れです"),
            PageTranslationWebExtension.Segment(id = "t2", text = "株価の動向について"),
            PageTranslationWebExtension.Segment(id = "t3", text = "明日の天気予報"),
        )
        val selected = SemanticSearchKeywordPrefilter.selectCandidates(
            segments = segments,
            query = "天気",
            maxCandidates = 2,
        )
        assertEquals(2, selected.size)
        assertTrue(selected.all { it.text.contains("天気") })
    }

    @Test
    fun parseSegmentIdsExtractsAllowedIdsOnly() {
        val parsed = parseSemanticSearchSegmentIds(
            modelOutput = "t2, t9, t1",
            allowedIds = setOf("t1", "t2"),
        )
        assertEquals(listOf("t2", "t1"), parsed)
    }

    @Test
    fun parseSegmentIdsReturnsEmptyForNone() {
        val parsed = parseSemanticSearchSegmentIds(
            modelOutput = "NONE",
            allowedIds = setOf("t1"),
        )
        assertTrue(parsed.isEmpty())
    }
}

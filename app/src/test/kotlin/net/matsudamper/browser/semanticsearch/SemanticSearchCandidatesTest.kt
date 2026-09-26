package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchCandidatesTest {
    @Test
    fun selectForInferenceTakesLeadingSegmentsWithoutQueryFilter() {
        val segments = listOf(
            PageTranslationWebExtension.Segment(id = "t1", text = "商品名"),
            PageTranslationWebExtension.Segment(id = "t2", text = "900円"),
            PageTranslationWebExtension.Segment(id = "t3", text = "価格の説明"),
        )
        val selected = SemanticSearchCandidates.selectForInference(
            segments = segments,
            maxCandidates = 2,
        )
        assertEquals(listOf("t1", "t2"), selected.map { it.id })
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

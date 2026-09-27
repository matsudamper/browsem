package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.data.SemanticSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticSearchModelsTest {
    @Test
    fun apiModelIdMapsLatestAliases() {
        assertEquals(
            SemanticSearchModels.FLASH_LATEST,
            SemanticSearchModels.apiModelId(SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST),
        )
        assertEquals(
            SemanticSearchModels.FLASH_LITE_LATEST,
            SemanticSearchModels.apiModelId(SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST),
        )
        assertNull(SemanticSearchModels.apiModelId(SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO))
    }
}

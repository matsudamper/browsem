package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.data.SemanticSearchProvider

internal object SemanticSearchModels {
    const val FLASH_LATEST = "gemini-flash-latest"
    const val FLASH_LITE_LATEST = "gemini-flash-lite-latest"

    fun apiModelId(provider: SemanticSearchProvider): String? {
        return when (provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO -> null
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST -> FLASH_LATEST
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST -> FLASH_LITE_LATEST
            SemanticSearchProvider.UNRECOGNIZED -> null
        }
    }
}

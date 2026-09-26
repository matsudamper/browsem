package net.matsudamper.browser.semanticsearch

import net.matsudamper.browser.data.SemanticSearchProvider
import net.matsudamper.browser.translate.PageTranslationWebExtension

internal data class SemanticSearchConfig(
    val provider: SemanticSearchProvider,
    val geminiNanoModelKey: String,
    val googleAiStudioApiKey: String,
)

internal object SemanticSearchRanker {
    suspend fun rankMatchingSegmentIds(
        query: String,
        candidates: List<PageTranslationWebExtension.Segment>,
        config: SemanticSearchConfig,
    ): List<String> {
        if (candidates.isEmpty()) return listOf()
        return when (config.provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO -> {
                GeminiNanoSemanticSearch(config.geminiNanoModelKey).rankMatchingSegmentIds(
                    query = query,
                    candidates = candidates,
                )
            }

            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST,
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST,
            -> {
                val modelId = SemanticSearchModels.apiModelId(config.provider)
                    ?: throw IllegalStateException("意味検索のモデルが選択されていません")
                GeminiApiSemanticSearch(
                    apiKey = config.googleAiStudioApiKey,
                    modelId = modelId,
                ).rankMatchingSegmentIds(
                    query = query,
                    candidates = candidates,
                )
            }

            SemanticSearchProvider.UNRECOGNIZED -> {
                throw IllegalStateException("意味検索の設定が不正です")
            }
        }
    }
}

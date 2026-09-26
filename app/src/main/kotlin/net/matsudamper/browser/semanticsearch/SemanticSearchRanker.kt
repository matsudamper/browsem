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
        onBatchStart: (current: Int, total: Int) -> Unit,
    ): List<String> {
        if (candidates.isEmpty()) return listOf()
        val batches = candidates.chunked(batchSize(config.provider))
        val matchedIds = linkedSetOf<String>()
        batches.forEachIndexed { index, batch ->
            onBatchStart(index + 1, batches.size)
            matchedIds.addAll(rankBatch(query, batch, config))
        }
        return candidates.map { it.id }.filter { matchedIds.contains(it) }
    }

    private suspend fun rankBatch(
        query: String,
        candidates: List<PageTranslationWebExtension.Segment>,
        config: SemanticSearchConfig,
    ): List<String> {
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

    private fun batchSize(provider: SemanticSearchProvider): Int {
        return when (provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO -> NANO_BATCH_SIZE

            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST,
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST,
            -> CLOUD_BATCH_SIZE

            SemanticSearchProvider.UNRECOGNIZED -> NANO_BATCH_SIZE
        }
    }

    private const val NANO_BATCH_SIZE = 12
    private const val CLOUD_BATCH_SIZE = 30
}

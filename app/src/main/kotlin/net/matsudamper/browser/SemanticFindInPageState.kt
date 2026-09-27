package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SemanticSearchProvider
import net.matsudamper.browser.feature.findinpage.FindInPageWebExtension
import net.matsudamper.browser.semanticsearch.SemanticSearchCandidates
import net.matsudamper.browser.semanticsearch.SemanticSearchConfig
import net.matsudamper.browser.semanticsearch.SemanticSearchRanker
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.GeckoSession

@Stable
internal class SemanticFindInPageState(
    private val coroutineScope: CoroutineScope,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val findInPageWebExtension: FindInPageWebExtension,
    private val session: () -> GeckoSession,
    private val currentPageUrl: () -> String,
    private val semanticSearchConfig: () -> SemanticSearchConfig,
) {
    private var modeOpen by mutableStateOf(false)
    private var searchJob: Job? = null
    private var searchGeneration = 0

    private var cachedSnapshot: PageTranslationWebExtension.PageSnapshot? = null
    private var cachedUrlKey: String? = null

    private var matchedSegmentIds: List<String> = listOf()
    private var matchedTexts: List<String> = listOf()

    val isVisible: Boolean get() = modeOpen

    var query by mutableStateOf("")
        private set

    var matchCurrent by mutableIntStateOf(0)
        private set

    var matchTotal by mutableIntStateOf(0)
        private set

    var isSearching by mutableStateOf(false)
        private set

    var queryError by mutableStateOf<String?>(null)
        private set

    var searchProgressMessage by mutableStateOf<String?>(null)
        private set

    var searchProgressDetail by mutableStateOf<String?>(null)
        private set

    fun open() {
        modeOpen = true
        query = ""
        matchCurrent = 0
        matchTotal = 0
        queryError = null
        isSearching = false
        searchProgressMessage = null
        searchProgressDetail = null
        matchedTexts = listOf()
    }

    fun close() {
        modeOpen = false
        searchJob?.cancel()
        searchJob = null
        clearPageHighlights()
        query = ""
        matchCurrent = 0
        matchTotal = 0
        queryError = null
        isSearching = false
        searchProgressMessage = null
        searchProgressDetail = null
        matchedSegmentIds = listOf()
        matchedTexts = listOf()
    }

    fun invalidatePageCache() {
        cachedSnapshot = null
        cachedUrlKey = null
    }

    fun onQueryChange(newQuery: String) {
        query = newQuery
        queryError = null
        searchJob?.cancel()
        isSearching = false
        searchProgressMessage = null
        searchProgressDetail = null
        if (newQuery.isBlank()) {
            matchCurrent = 0
            matchTotal = 0
            matchedSegmentIds = listOf()
            matchedTexts = listOf()
            clearPageHighlights()
        }
    }

    fun findNext() {
        if (matchedTexts.isEmpty()) return
        val next = if (matchCurrent >= matchedTexts.size) 1 else matchCurrent + 1
        focusMatch(next)
    }

    fun findPrevious() {
        if (matchedTexts.isEmpty()) return
        val previous = if (matchCurrent <= 1) matchedTexts.size else matchCurrent - 1
        focusMatch(previous)
    }

    fun runSearchImmediately() {
        if (query.isBlank()) return
        coroutineScope.launch {
            runSearch()
        }
    }

    private suspend fun runSearch() {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        val job = coroutineScope.launch {
            isSearching = true
            queryError = null
            searchProgressMessage = "ページのテキストを取得しています"
            searchProgressDetail = null
            try {
                val config = semanticSearchConfig()
                val snapshot = loadSnapshot()
                val maxCandidates = maxCandidatesFor(config.provider)
                val candidates = SemanticSearchCandidates.selectForInference(
                    segments = snapshot.segments,
                    maxCandidates = maxCandidates,
                )
                if (candidates.isEmpty()) {
                    applyMatches(listOf())
                    return@launch
                }
                val rankedIds = SemanticSearchRanker.rankMatchingSegmentIds(
                    query = trimmedQuery,
                    candidates = candidates,
                    config = config,
                    onBatchStart = { current, total ->
                        searchProgressMessage = inferenceProgressMessage(config.provider)
                        searchProgressDetail = inferenceProgressDetail(
                            provider = config.provider,
                            batchCurrent = current,
                            batchTotal = total,
                            coveredCount = candidates.size,
                            pageSegmentCount = snapshot.segments.size,
                        )
                    },
                )
                applyMatches(rankedIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                queryError = error.message ?: "意味検索に失敗しました"
                matchCurrent = 0
                matchTotal = 0
                matchedSegmentIds = listOf()
                matchedTexts = listOf()
                clearPageHighlights()
            } finally {
                if (generation == searchGeneration) {
                    isSearching = false
                    searchProgressMessage = null
                    searchProgressDetail = null
                }
            }
        }
        searchJob = job
        job.join()
    }

    private suspend fun loadSnapshot(): PageTranslationWebExtension.PageSnapshot {
        val urlKey = currentPageUrl().substringBefore('#')
        val snapshot = pageTranslationWebExtension.scanSemanticPage(
            session = session(),
            expectedUrl = currentPageUrl(),
        )
        cachedSnapshot = snapshot
        cachedUrlKey = urlKey
        return snapshot
    }

    private fun applyMatches(segmentIds: List<String>) {
        val snapshot = cachedSnapshot
        val segmentTexts = resolveSegmentTexts(segmentIds, snapshot)
        matchedSegmentIds = segmentIds
        matchedTexts = segmentTexts
        matchTotal = segmentTexts.size
        matchCurrent = if (segmentTexts.isEmpty()) 0 else 1
        if (segmentTexts.isEmpty()) {
            clearPageHighlights()
            if (segmentIds.isNotEmpty()) {
                queryError = "ページ上に結果を表示できませんでした"
            }
            return
        }
        focusMatch(1)
    }

    private fun focusMatch(index: Int) {
        if (matchedTexts.isEmpty()) return
        val safeIndex = index.coerceIn(1, matchedTexts.size)
        matchCurrent = safeIndex
        val searchText = matchedTexts[safeIndex - 1].take(FIND_IN_PAGE_MAX_QUERY_LENGTH)
        if (searchText.isBlank()) {
            queryError = "ページ上に結果を表示できませんでした"
            return
        }
        findInPageWebExtension.search(
            session = session(),
            query = searchText,
            isRegex = false,
        )
    }

    private fun clearPageHighlights() {
        findInPageWebExtension.clear(session())
        pageTranslationWebExtension.clearSemanticHighlights(session())
    }

    private fun resolveSegmentTexts(
        segmentIds: List<String>,
        snapshot: PageTranslationWebExtension.PageSnapshot?,
    ): List<String> {
        if (snapshot == null) return listOf()
        val segmentsById = snapshot.segments.associateBy { it.id }
        return segmentIds.mapNotNull { id ->
            segmentsById[id]?.text?.takeIf { it.isNotBlank() }
        }
    }

    private fun inferenceProgressMessage(provider: SemanticSearchProvider): String {
        return when (provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO -> "端末内 AI で推論しています"

            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST,
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST,
            -> "Google API を呼び出しています"

            SemanticSearchProvider.UNRECOGNIZED -> "推論しています"
        }
    }

    private fun inferenceProgressDetail(
        provider: SemanticSearchProvider,
        batchCurrent: Int,
        batchTotal: Int,
        coveredCount: Int,
        pageSegmentCount: Int,
    ): String {
        val coverage = "対象 $coveredCount/$pageSegmentCount セグメント"
        return when (provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO ->
                "推論 $batchCurrent/$batchTotal・$coverage・ネットワーク送信なし"

            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST,
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST,
            -> "API 呼び出し $batchCurrent/$batchTotal・$coverage"

            SemanticSearchProvider.UNRECOGNIZED ->
                "推論 $batchCurrent/$batchTotal・$coverage"
        }
    }

    private fun maxCandidatesFor(provider: SemanticSearchProvider): Int {
        return when (provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO -> MAX_NANO_CANDIDATES

            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST,
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST,
            -> MAX_CLOUD_CANDIDATES

            SemanticSearchProvider.UNRECOGNIZED -> MAX_NANO_CANDIDATES
        }
    }

    companion object {
        private const val MAX_NANO_CANDIDATES = 48
        private const val MAX_CLOUD_CANDIDATES = 180
        private const val FIND_IN_PAGE_MAX_QUERY_LENGTH = 500
    }
}

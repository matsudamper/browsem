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
import net.matsudamper.browser.semanticsearch.SemanticSearchConfig
import net.matsudamper.browser.semanticsearch.SemanticSearchKeywordPrefilter
import net.matsudamper.browser.semanticsearch.SemanticSearchRanker
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.GeckoSession

@Stable
internal class SemanticFindInPageState(
    private val coroutineScope: CoroutineScope,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val session: () -> GeckoSession,
    private val currentPageUrl: () -> String,
    private val semanticSearchConfig: () -> SemanticSearchConfig,
) {
    private var modeOpen by mutableStateOf(false)
    private var searchJob: Job? = null

    private var cachedSnapshot: PageTranslationWebExtension.PageSnapshot? = null
    private var cachedUrlKey: String? = null

    private var matchedSegmentIds: List<String> = listOf()

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
    }

    fun close() {
        modeOpen = false
        searchJob?.cancel()
        searchJob = null
        pageTranslationWebExtension.clearSemanticHighlights(session())
        query = ""
        matchCurrent = 0
        matchTotal = 0
        queryError = null
        isSearching = false
        searchProgressMessage = null
        searchProgressDetail = null
        matchedSegmentIds = listOf()
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
            pageTranslationWebExtension.clearSemanticHighlights(session())
        }
    }

    fun findNext() {
        if (matchedSegmentIds.isEmpty()) return
        val next = if (matchCurrent >= matchedSegmentIds.size) 1 else matchCurrent + 1
        focusMatch(next)
    }

    fun findPrevious() {
        if (matchedSegmentIds.isEmpty()) return
        val previous = if (matchCurrent <= 1) matchedSegmentIds.size else matchCurrent - 1
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
        val job = coroutineScope.launch {
            isSearching = true
            queryError = null
            searchProgressMessage = "ページのテキストを取得しています"
            searchProgressDetail = null
            try {
                val config = semanticSearchConfig()
                val snapshot = loadSnapshot()
                val maxCandidates = maxCandidatesFor(config.provider)
                searchProgressMessage = "候補を絞り込んでいます"
                searchProgressDetail = "最大 $maxCandidates 件まで"
                val candidates = SemanticSearchKeywordPrefilter.selectCandidates(
                    segments = snapshot.segments,
                    query = trimmedQuery,
                    maxCandidates = maxCandidates,
                )
                if (candidates.isEmpty()) {
                    applyMatches(snapshot.documentId, listOf())
                    return@launch
                }
                val inferenceDetail = inferenceProgressDetail(
                    provider = config.provider,
                    candidateCount = candidates.size,
                )
                searchProgressMessage = inferenceProgressMessage(config.provider)
                searchProgressDetail = inferenceDetail
                val rankedIds = SemanticSearchRanker.rankMatchingSegmentIds(
                    query = trimmedQuery,
                    candidates = candidates,
                    config = config,
                )
                applyMatches(snapshot.documentId, rankedIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                queryError = error.message ?: "意味検索に失敗しました"
                matchCurrent = 0
                matchTotal = 0
                matchedSegmentIds = listOf()
                pageTranslationWebExtension.clearSemanticHighlights(session())
            } finally {
                isSearching = false
                searchProgressMessage = null
                searchProgressDetail = null
            }
        }
        searchJob = job
        job.join()
    }

    private suspend fun loadSnapshot(): PageTranslationWebExtension.PageSnapshot {
        val urlKey = currentPageUrl().substringBefore('#')
        val cached = cachedSnapshot
        if (cached != null && cachedUrlKey == urlKey) {
            return cached
        }
        val snapshot = pageTranslationWebExtension.scanSemanticPage(
            session = session(),
            expectedUrl = currentPageUrl(),
        )
        cachedSnapshot = snapshot
        cachedUrlKey = urlKey
        return snapshot
    }

    private fun applyMatches(documentId: String, segmentIds: List<String>) {
        matchedSegmentIds = segmentIds
        matchTotal = segmentIds.size
        matchCurrent = if (segmentIds.isEmpty()) 0 else 1
        if (segmentIds.isEmpty()) {
            pageTranslationWebExtension.clearSemanticHighlights(session())
            return
        }
        pageTranslationWebExtension.applySemanticHighlights(
            session = session(),
            documentId = documentId,
            segmentIds = segmentIds,
            focusIndex = 0,
        )
    }

    private fun focusMatch(index: Int) {
        if (matchedSegmentIds.isEmpty()) return
        val snapshot = cachedSnapshot ?: return
        val safeIndex = (index - 1).coerceIn(0, matchedSegmentIds.size - 1)
        matchCurrent = safeIndex + 1
        pageTranslationWebExtension.applySemanticHighlights(
            session = session(),
            documentId = snapshot.documentId,
            segmentIds = matchedSegmentIds,
            focusIndex = safeIndex,
        )
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
        candidateCount: Int,
    ): String {
        return when (provider) {
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_NANO ->
                "候補 $candidateCount 件・ネットワーク送信なし"

            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LATEST,
            SemanticSearchProvider.SEMANTIC_SEARCH_GEMINI_FLASH_LITE_LATEST,
            -> "候補 $candidateCount 件・API 呼び出し 1/1"

            SemanticSearchProvider.UNRECOGNIZED -> "候補 $candidateCount 件"
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
        private const val MAX_NANO_CANDIDATES = 16
        private const val MAX_CLOUD_CANDIDATES = 24
    }
}

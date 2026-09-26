package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.matsudamper.browser.semanticsearch.GeminiNanoSemanticSearch
import net.matsudamper.browser.semanticsearch.SemanticSearchKeywordPrefilter
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.GeckoSession

@Stable
internal class SemanticFindInPageState(
    private val coroutineScope: CoroutineScope,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val session: () -> GeckoSession,
    private val currentPageUrl: () -> String,
    private val geminiNanoModelKey: () -> String,
) {
    private var modeOpen by mutableStateOf(false)
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

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

    fun open() {
        modeOpen = true
        query = ""
        matchCurrent = 0
        matchTotal = 0
        queryError = null
        isSearching = false
    }

    fun close() {
        modeOpen = false
        searchJob?.cancel()
        debounceJob?.cancel()
        searchJob = null
        debounceJob = null
        pageTranslationWebExtension.clearSemanticHighlights(session())
        query = ""
        matchCurrent = 0
        matchTotal = 0
        queryError = null
        isSearching = false
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
        debounceJob?.cancel()
        if (newQuery.isBlank()) {
            isSearching = false
            matchCurrent = 0
            matchTotal = 0
            matchedSegmentIds = listOf()
            pageTranslationWebExtension.clearSemanticHighlights(session())
            return
        }
        debounceJob = coroutineScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            runSearch()
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
        debounceJob?.cancel()
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
            try {
                val snapshot = loadSnapshot()
                val candidates = SemanticSearchKeywordPrefilter.selectCandidates(
                    segments = snapshot.segments,
                    query = trimmedQuery,
                    maxCandidates = MAX_GEMINI_CANDIDATES,
                )
                if (candidates.isEmpty()) {
                    applyMatches(snapshot.documentId, listOf())
                    return@launch
                }
                val rankedIds = GeminiNanoSemanticSearch(geminiNanoModelKey()).rankMatchingSegmentIds(
                    query = trimmedQuery,
                    candidates = candidates,
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

    companion object {
        private const val QUERY_DEBOUNCE_MS = 450L
        private const val MAX_GEMINI_CANDIDATES = 16
    }
}

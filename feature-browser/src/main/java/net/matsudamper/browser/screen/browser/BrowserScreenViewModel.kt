package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.Closeable
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedEnableWebSuggestions
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository

data class UrlBarSuggestionsUiState(
    val historySuggestions: List<HistoryEntry> = emptyList(),
    val webSuggestions: List<String> = emptyList(),
    val isLoadingWebSuggestions: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserScreenViewModel(
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val webSuggestionRepository: WebSuggestionRepository,
) : ViewModel(), Closeable {
    // ViewModel継承時はonCleared()でキャンセル、remember()使用時はclose()でキャンセル
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    override fun close() {
        scope.cancel()
    }

    private val suggestionQuery = MutableStateFlow("")

    private val historySuggestions: StateFlow<List<HistoryEntry>> = suggestionQuery
        .map(String::trim)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) {
                historyRepository.getRecentSuggestions(limit = HISTORY_SUGGESTION_LIMIT)
            } else {
                historyRepository.searchSuggestions(
                    query = query,
                    limit = HISTORY_SUGGESTION_LIMIT,
                )
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    private val webSuggestions: StateFlow<WebSuggestionState> = combine(
        suggestionQuery
            .map(String::trim)
            .distinctUntilChanged(),
        settingsRepository.settings,
    ) { query, settings ->
        WebSuggestionParams(
            query = query,
            searchProvider = settings.searchProvider,
            enabled = settings.resolvedEnableWebSuggestions(),
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { params ->
            flow {
                emit(WebSuggestionState())

                if (!params.enabled || !shouldFetchWebSuggestions(params.query)) {
                    return@flow
                }

                delay(WEB_SUGGESTION_DEBOUNCE_MILLIS)
                emit(WebSuggestionState(isLoading = true))
                emit(
                    WebSuggestionState(
                        suggestions = webSuggestionRepository.getSuggestions(
                            searchProvider = params.searchProvider,
                            query = params.query,
                        ),
                    ),
                )
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = WebSuggestionState(),
        )

    val urlBarSuggestions: StateFlow<UrlBarSuggestionsUiState> = combine(
        historySuggestions,
        webSuggestions,
    ) { history, web ->
        UrlBarSuggestionsUiState(
            historySuggestions = history,
            webSuggestions = web.suggestions,
            isLoadingWebSuggestions = web.isLoading,
        )
    }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UrlBarSuggestionsUiState(),
        )

    suspend fun recordHistory(url: String, title: String): Long {
        return historyRepository.recordVisit(url, title)
    }

    suspend fun updateHistoryTitle(id: Long, title: String) {
        historyRepository.updateTitle(id, title)
    }

    fun onUrlInputChanged(query: String) {
        suggestionQuery.value = query
    }

    companion object {
        private const val HISTORY_SUGGESTION_LIMIT = 8
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val WEB_SUGGESTION_DEBOUNCE_MILLIS = 250L
    }
}

internal fun shouldFetchWebSuggestions(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isBlank()) {
        return false
    }
    if (SCHEME_PREFIX_REGEX.containsMatchIn(trimmed)) {
        return false
    }
    if (!trimmed.contains(" ") && trimmed.contains(".")) {
        return false
    }
    return true
}

private val SCHEME_PREFIX_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

private data class WebSuggestionParams(
    val query: String,
    val searchProvider: SearchProvider,
    val enabled: Boolean,
)

private data class WebSuggestionState(
    val suggestions: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

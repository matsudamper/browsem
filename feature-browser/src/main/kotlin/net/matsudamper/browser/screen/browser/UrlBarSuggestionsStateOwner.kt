package net.matsudamper.browser.screen.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedEnableWebSuggestions
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.ui.browser.BrowserScreenUiState
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState

@OptIn(ExperimentalCoroutinesApi::class)
internal class UrlBarSuggestionsStateOwner(
    scope: CoroutineScope,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val webSuggestionRepository: WebSuggestionRepository,
) {
    private val suggestionQuery = MutableStateFlow("")
    private val viewModelStateFlow = MutableStateFlow(UrlBarSuggestionsViewModelState())
    private val webSuggestionInputFlow = MutableStateFlow(WebSuggestionInput())

    val callbacks = object : BrowserScreenUiState.Callbacks {
        override suspend fun onHistoryRecord(url: String, title: String): Long {
            return historyRepository.recordVisit(url, title)
        }

        override suspend fun onHistoryTitleUpdate(id: Long, title: String) {
            historyRepository.updateTitle(id, title)
        }

        override fun onUrlInputChanged(query: String) {
            suggestionQuery.value = query
        }
    }

    val urlBarSuggestions: StateFlow<UrlBarSuggestionsUiState> = MutableStateFlow(
        UrlBarSuggestionsUiState(),
    ).also { uiStateFlow ->
        scope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    UrlBarSuggestionsUiState(
                        historySuggestions = state.historySuggestionState.suggestions,
                        isLoadingHistorySuggestions = state.historySuggestionState.isLoading,
                        webSuggestions = state.webSuggestionState.suggestions,
                        isLoadingWebSuggestions = state.webSuggestionState.isLoading,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        scope.launch {
            suggestionQuery
                .map(String::trim)
                .distinctUntilChanged()
                .collectLatest { query ->
                    viewModelStateFlow.update {
                        it.copy(historySuggestionState = HistorySuggestionState(isLoading = true))
                    }
                    val historyFlow = if (query.isBlank()) {
                        historyRepository.getRecentSuggestions(limit = HISTORY_SUGGESTION_LIMIT)
                    } else {
                        historyRepository.searchSuggestions(
                            query = query,
                            limit = HISTORY_SUGGESTION_LIMIT,
                        )
                    }
                    historyFlow.collectLatest { suggestions ->
                        viewModelStateFlow.update {
                            it.copy(
                                historySuggestionState = HistorySuggestionState(
                                    suggestions = suggestions,
                                    isLoading = false,
                                ),
                            )
                        }
                    }
                }
        }
        scope.launch {
            suggestionQuery
                .map(String::trim)
                .distinctUntilChanged()
                .collectLatest { query ->
                    webSuggestionInputFlow.update { it.copy(query = query) }
                }
        }
        scope.launch {
            settingsRepository.settings.collectLatest { settings ->
                webSuggestionInputFlow.update {
                    it.copy(
                        searchProvider = settings.searchProvider,
                        enabled = settings.resolvedEnableWebSuggestions(),
                    )
                }
            }
        }
        scope.launch {
            webSuggestionInputFlow.collectLatest { params ->
                viewModelStateFlow.update { it.copy(webSuggestionState = WebSuggestionState()) }

                val searchProvider = params.searchProvider
                if (searchProvider == null ||
                    !params.enabled ||
                    !searchProvider.supportsWebSuggestions() ||
                    !shouldFetchWebSuggestions(params.query)
                ) {
                    return@collectLatest
                }

                delay(WEB_SUGGESTION_DEBOUNCE_MILLIS)
                viewModelStateFlow.update {
                    it.copy(webSuggestionState = WebSuggestionState(isLoading = true))
                }
                val suggestions = webSuggestionRepository.getSuggestions(
                    searchProvider = searchProvider,
                    query = params.query,
                )
                viewModelStateFlow.update {
                    it.copy(
                        webSuggestionState = WebSuggestionState(
                            suggestions = suggestions,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val HISTORY_SUGGESTION_LIMIT = 8
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

private fun SearchProvider.supportsWebSuggestions(): Boolean {
    return this == SearchProvider.GOOGLE || this == SearchProvider.DUCKDUCKGO
}

private data class WebSuggestionInput(
    val query: String = "",
    val searchProvider: SearchProvider? = null,
    val enabled: Boolean = false,
)

private data class WebSuggestionState(
    val suggestions: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

private data class HistorySuggestionState(
    val suggestions: List<HistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
)

private data class UrlBarSuggestionsViewModelState(
    val historySuggestionState: HistorySuggestionState = HistorySuggestionState(),
    val webSuggestionState: WebSuggestionState = WebSuggestionState(),
)

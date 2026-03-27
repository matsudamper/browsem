package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedEnableWebSuggestions
import net.matsudamper.browser.data.tab.TabGroupAssignment
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
    private val tabGroupRepository: TabGroupRepository? = null,
    browserTabsFlow: Flow<List<BrowserTab>> = flowOf(emptyList()),
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

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())
    private val webSuggestionInputFlow = MutableStateFlow(WebSuggestionInput())

    private val callbacks = object : BrowserScreenUiState.Callbacks {
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

    private val suggestionQuery = MutableStateFlow("")

    private val historySuggestionsFlow: Flow<List<HistoryEntry>> = suggestionQuery
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
    val uiState: StateFlow<BrowserScreenUiState> = MutableStateFlow(
        BrowserScreenUiState(
            urlBarSuggestions = UrlBarSuggestionsUiState(),
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        scope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    BrowserScreenUiState(
                        urlBarSuggestions = UrlBarSuggestionsUiState(
                            historySuggestions = state.historySuggestions,
                            webSuggestions = state.webSuggestionState.suggestions,
                            isLoadingWebSuggestions = state.webSuggestionState.isLoading,
                        ),
                        orderedBrowserTabs = state.resolveOrderedBrowserTabs(),
                        callbacks = callbacks,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        scope.launch {
            historySuggestionsFlow.collectLatest { suggestions ->
                viewModelStateFlow.update { it.copy(historySuggestions = suggestions) }
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
            webSuggestionInputFlow
                .collectLatest { params ->
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
                    viewModelStateFlow.update { it.copy(webSuggestionState = WebSuggestionState(isLoading = true)) }
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
        scope.launch {
            browserTabsFlow
                .distinctUntilChanged()
                .collectLatest { browserTabs ->
                    viewModelStateFlow.update { it.copy(browserTabs = browserTabs) }
                }
        }
        if (tabGroupRepository != null) {
            scope.launch {
                tabGroupRepository.observeGroups().collectLatest { groups ->
                    viewModelStateFlow.update { it.copy(tabGroups = groups) }
                }
            }
            scope.launch {
                tabGroupRepository.observeTabGroupAssignments().collectLatest { assignments ->
                    viewModelStateFlow.update { it.copy(tabGroupAssignments = assignments) }
                }
            }
        }
    }

    interface Event

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

private data class ViewModelState(
    val historySuggestions: List<HistoryEntry> = emptyList(),
    val webSuggestionState: WebSuggestionState = WebSuggestionState(),
    val tabGroups: List<TabGroupData> = emptyList(),
    val tabGroupAssignments: List<TabGroupAssignment> = emptyList(),
    val browserTabs: List<BrowserTab> = emptyList(),
) {
    fun resolveOrderedBrowserTabs(): List<BrowserTab> {
        val assignmentMap = tabGroupAssignments.associate { it.tabId to it.groupId }
        val groupedTabs = tabGroups.flatMap { group ->
            browserTabs.filter { tab -> assignmentMap[tab.tabId] == group.id.value }
        }
        val groupedTabIds = groupedTabs.map { it.tabId }.toSet()
        val ungroupedTabs = browserTabs.filter { tab -> tab.tabId !in groupedTabIds }
        return groupedTabs + ungroupedTabs
    }
}

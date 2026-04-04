package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.tab.TabGroupAssignment
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.ui.browser.BrowserScreenUiState
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState

class BrowserScreenViewModel(
    historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
    webSuggestionRepository: WebSuggestionRepository,
    tabGroupRepository: TabGroupRepository,
    browserTabsFlow: Flow<List<BrowserTab>>,
    screenTabId: String,
) : ViewModel(), Closeable {
    // ViewModel継承時はonCleared()でキャンセル、remember()使用時はclose()でキャンセル
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val urlBarSuggestionsStateOwner = UrlBarSuggestionsStateOwner(
        scope = scope,
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
        webSuggestionRepository = webSuggestionRepository,
    )
    private val callbacks = urlBarSuggestionsStateOwner.callbacks

    private val viewModelStateFlow = MutableStateFlow(
        ViewModelState(screenTabId = screenTabId),
    )

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    override fun close() {
        scope.cancel()
    }

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    val uiState: StateFlow<BrowserScreenUiState> = MutableStateFlow(
        BrowserScreenUiState(
            urlBarSuggestions = UrlBarSuggestionsUiState(),
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        scope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    val adjacentTabs = state.resolveAdjacentTabs()
                    BrowserScreenUiState(
                        urlBarSuggestions = state.urlBarSuggestions,
                        swipePreview = BrowserScreenUiState.SwipePreviewUiState(
                            previousTab = adjacentTabs.previousTab,
                            nextTab = adjacentTabs.nextTab,
                        ),
                        callbacks = callbacks,
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        scope.launch {
            urlBarSuggestionsStateOwner.urlBarSuggestions.collectLatest { suggestions ->
                viewModelStateFlow.update { it.copy(urlBarSuggestions = suggestions) }
            }
        }
        scope.launch {
            browserTabsFlow
                .distinctUntilChanged()
                .collectLatest { browserTabs ->
                    viewModelStateFlow.update {
                        it.copy(browserTabs = browserTabs).withResolvedOrderedBrowserTabs()
                    }
                }
        }
        scope.launch {
            tabGroupRepository.observeGroups().collectLatest { groups ->
                viewModelStateFlow.update {
                    it.copy(tabGroups = groups).withResolvedOrderedBrowserTabs()
                }
            }
        }
        scope.launch {
            tabGroupRepository.observeTabGroupAssignments().collectLatest { assignments ->
                viewModelStateFlow.update {
                    it.copy(tabGroupAssignments = assignments).withResolvedOrderedBrowserTabs()
                }
            }
        }
    }

    interface Event
}

private data class ViewModelState(
    val urlBarSuggestions: UrlBarSuggestionsUiState = UrlBarSuggestionsUiState(),
    val tabGroups: List<TabGroupData> = emptyList(),
    val tabGroupAssignments: List<TabGroupAssignment> = emptyList(),
    val browserTabs: List<BrowserTab> = emptyList(),
    val orderedBrowserTabs: List<BrowserTab> = emptyList(),
    val screenTabId: String? = null,
) {
    fun withResolvedOrderedBrowserTabs(): ViewModelState {
        val orderedBrowserTabs = resolveOrderedBrowserTabs()
        return copy(
            orderedBrowserTabs = orderedBrowserTabs,
        )
    }

    fun resolveAdjacentTabs(): AdjacentTabs {
        val adjacentTabIds = resolveAdjacentTabIds(
            orderedTabIds = orderedBrowserTabs.map(BrowserTab::tabId),
            anchorTabId = screenTabId,
        )
        return AdjacentTabs(
            previousTab = adjacentTabIds.previousTabId?.let(::findTab),
            nextTab = adjacentTabIds.nextTabId?.let(::findTab),
        )
    }

    private fun findTab(tabId: String): BrowserTab? {
        return orderedBrowserTabs.firstOrNull { it.tabId == tabId }
    }

    private fun resolveOrderedBrowserTabs(): List<BrowserTab> {
        val assignmentMap = tabGroupAssignments.associate { it.tabId to it.groupId }
        val groupedTabs = tabGroups.flatMap { group ->
            browserTabs.filter { tab -> assignmentMap[tab.tabId] == group.id.value }
        }
        val groupedTabIds = groupedTabs.map { it.tabId }.toSet()
        val ungroupedTabs = browserTabs.filter { tab -> tab.tabId !in groupedTabIds }
        return groupedTabs + ungroupedTabs
    }
}

private data class AdjacentTabs(
    val previousTab: BrowserTab? = null,
    val nextTab: BrowserTab? = null,
)

internal fun resolveAdjacentTabIds(
    orderedTabIds: List<String>,
    anchorTabId: String?,
): AdjacentTabIds {
    val tabId = anchorTabId ?: return AdjacentTabIds()
    val currentIndex = orderedTabIds.indexOf(tabId)
    if (currentIndex < 0) return AdjacentTabIds()
    return AdjacentTabIds(
        previousTabId = orderedTabIds.getOrNull(currentIndex - 1),
        nextTabId = orderedTabIds.getOrNull(currentIndex + 1),
    )
}

internal data class AdjacentTabIds(
    val previousTabId: String? = null,
    val nextTabId: String? = null,
)

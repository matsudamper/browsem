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
            groupTabCount = null,
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
                        groupTabCount = state.resolveGroupTabCount(),
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
                    it.copy(
                        tabGroups = groups,
                        tabGroupsLoaded = true,
                    ).withResolvedOrderedBrowserTabs()
                }
            }
        }
        scope.launch {
            tabGroupRepository.observeTabGroupAssignments().collectLatest { assignments ->
                viewModelStateFlow.update {
                    it.copy(
                        tabGroupAssignments = assignments,
                        tabGroupAssignmentsLoaded = true,
                    ).withResolvedOrderedBrowserTabs()
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
    // tabGroups / tabGroupAssignments の Flow が初回値を発行済みかどうか。
    // 未ロード時は空リストと「グループが存在しない」状態が区別できず、
    // 別グループのタブまでスワイプ移動できてしまうため、ロード完了まで待つ判定に使う。
    val tabGroupsLoaded: Boolean = false,
    val tabGroupAssignmentsLoaded: Boolean = false,
) {
    fun withResolvedOrderedBrowserTabs(): ViewModelState {
        val orderedBrowserTabs = resolveOrderedBrowserTabs()
        return copy(
            orderedBrowserTabs = orderedBrowserTabs,
        )
    }

    private fun isTabGroupStateLoaded(): Boolean {
        return tabGroupsLoaded && tabGroupAssignmentsLoaded
    }

    fun resolveAdjacentTabs(): AdjacentTabs {
        // タブグループの状態がロード完了するまでは前後タブを解決しない。
        // 未ロード時はすべてのタブが「グループ未割り当て」と見なされてしまい、
        // グループ間でスワイプ移動できてしまう不具合を防ぐ。
        if (!isTabGroupStateLoaded()) return AdjacentTabs()
        // 同じタブグループ内のタブのみを対象にして前後タブを解決する。
        // グループ間の移動を防ぐため、現在のタブが属するグループのタブだけに絞り込む。
        val sameGroupTabIds = resolveGroupTabIds()
        val adjacentTabIds = resolveAdjacentTabIds(
            orderedTabIds = sameGroupTabIds,
            anchorTabId = screenTabId,
        )
        return AdjacentTabs(
            previousTab = adjacentTabIds.previousTabId?.let(::findTab),
            nextTab = adjacentTabIds.nextTabId?.let(::findTab),
        )
    }

    /**
     * 現在のタブと同じグループに属するタブIDのリストを返す。
     * グループ未割り当ての場合は未割り当てタブのみを返す。
     */
    private fun resolveGroupTabIds(): List<String> {
        val assignmentMap = tabGroupAssignments.associate { it.tabId to it.groupId }
        val knownGroupIds = tabGroups.map { it.id.value }.toSet()
        val currentGroupId = assignmentMap[screenTabId]?.takeIf { it in knownGroupIds }
        return orderedBrowserTabs
            .filter { tab ->
                val tabGroupId = assignmentMap[tab.tabId]?.takeIf { it in knownGroupIds }
                tabGroupId == currentGroupId
            }
            .map { it.tabId }
    }

    private fun findTab(tabId: String): BrowserTab? {
        return orderedBrowserTabs.firstOrNull { it.tabId == tabId }
    }

    fun resolveGroupTabCount(): Int? {
        if (browserTabs.isEmpty()) return null
        val assignmentMap = tabGroupAssignments.associate { it.tabId to it.groupId }
        val knownGroupIds = tabGroups.map { it.id.value }.toSet()
        val currentGroupId = assignmentMap[screenTabId]?.takeIf { it in knownGroupIds } ?: return null
        return browserTabs.count { tab -> assignmentMap[tab.tabId] == currentGroupId }
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

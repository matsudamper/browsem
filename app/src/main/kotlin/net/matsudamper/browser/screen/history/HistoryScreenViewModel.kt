package net.matsudamper.browser.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.ui.history.HistoryScreenUiState

internal class HistoryScreenViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())
    private val entryListFlow = MutableStateFlow<EntryListData?>(null)
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : HistoryScreenUiState.Callbacks {
        override fun onSearchQueryChange(query: String) {
            viewModelStateFlow.update { it.copy(searchQuery = query) }
        }

        override fun onClickDeleteAll() {
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = true) }
        }

        override fun onConfirmDeleteAll() {
            viewModelScope.launch { historyRepository.deleteAll() }
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = false) }
        }

        override fun onDismissDeleteAllDialog() {
            viewModelStateFlow.update { it.copy(showDeleteAllDialog = false) }
        }
    }

    val uiState: StateFlow<HistoryScreenUiState> = MutableStateFlow(
        HistoryScreenUiState(
            callbacks = callbacks,
            searchQuery = "",
            entryList = null,
            showDeleteAllDialog = false,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                viewModelStateFlow.map { it.searchQuery }.distinctUntilChanged(),
                viewModelStateFlow.map { it.showDeleteAllDialog }.distinctUntilChanged(),
                entryListFlow,
            ) { searchQuery, showDeleteAllDialog, entryListData ->
                HistoryScreenUiState(
                    callbacks = callbacks,
                    searchQuery = searchQuery,
                    entryList = entryListData?.toUiEntryList(),
                    showDeleteAllDialog = showDeleteAllDialog,
                )
            }.collect { uiState ->
                uiStateFlow.value = uiState
            }
        }
    }.asStateFlow()

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            viewModelStateFlow
                .map { it.searchQuery }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    val entriesFlow = if (query.isBlank()) {
                        historyRepository.getRecent()
                    } else {
                        historyRepository.search(query)
                    }
                    entriesFlow.map { entries -> EntryListData(searchQuery = query, entries = entries) }
                }
                .collect { entryListData ->
                    entryListFlow.value = entryListData
                }
        }
    }

    private fun EntryListData.toUiEntryList(): HistoryScreenUiState.EntryList {
        return HistoryScreenUiState.EntryList(
            searchQuery = searchQuery,
            entries = entries.map(::toEntryItem),
        )
    }

    private fun toEntryItem(entry: HistoryEntry): HistoryScreenUiState.EntryItem {
        return HistoryScreenUiState.EntryItem(
            id = entry.id,
            title = entry.title,
            url = entry.url,
            visitedAt = entry.visitedAt,
            listener = object : HistoryScreenUiState.EntryItem.Listener {
                override fun onClick() {
                    eventHandler.trySend { it.navigateToUrl(entry.url) }
                }

                override fun onDelete() {
                    viewModelScope.launch { historyRepository.deleteById(entry.id) }
                }
            },
        )
    }

    interface Event {
        fun navigateToUrl(url: String)
    }

    private data class EntryListData(
        val searchQuery: String,
        val entries: List<HistoryEntry>,
    )

    data class ViewModelState(
        val searchQuery: String = "",
        val showDeleteAllDialog: Boolean = false,
    )
}

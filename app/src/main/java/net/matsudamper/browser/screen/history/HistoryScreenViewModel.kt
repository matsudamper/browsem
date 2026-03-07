package net.matsudamper.browser.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository

internal class HistoryScreenViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {
    val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyEntries: Flow<List<HistoryEntry>> = searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) historyRepository.getRecent() else historyRepository.search(query)
        }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { historyRepository.deleteById(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { historyRepository.deleteAll() }
    }
}

package net.matsudamper.browser.screen.history

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.data.history.HistoryEntry

internal class HistoryScreenViewModel(
    private val browserViewModel: BrowserViewModel,
) : ViewModel() {
    val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyEntries: Flow<List<HistoryEntry>> = searchQuery
        .flatMapLatest { query -> browserViewModel.searchHistory(query) }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun deleteEntry(id: Long) {
        browserViewModel.deleteHistoryEntry(id)
    }

    fun deleteAll() {
        browserViewModel.deleteAllHistory()
    }
}

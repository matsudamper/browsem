package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserScreenViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {
    private val suggestionQuery = MutableStateFlow("")

    val historySuggestions: StateFlow<List<HistoryEntry>> = suggestionQuery
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
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
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
    }
}

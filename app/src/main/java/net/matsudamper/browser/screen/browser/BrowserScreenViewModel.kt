package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import net.matsudamper.browser.SettingsUiState
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository

internal class BrowserScreenViewModel(
    val settingsUiState: StateFlow<SettingsUiState?>,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    suspend fun recordHistory(url: String, title: String): Long {
        return historyRepository.recordVisit(url, title)
    }

    suspend fun updateHistoryTitle(id: Long, title: String) {
        historyRepository.updateTitle(id, title)
    }

    fun searchHistory(query: String, limit: Int = 8): Flow<List<HistoryEntry>> {
        return if (query.isBlank()) {
            historyRepository.getRecentSuggestions(limit = limit)
        } else {
            historyRepository.searchSuggestions(query = query, limit = limit)
        }
    }
}

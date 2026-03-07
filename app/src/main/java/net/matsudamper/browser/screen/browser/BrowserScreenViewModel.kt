package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.SettingsUiState
import net.matsudamper.browser.data.history.HistoryEntry

internal class BrowserScreenViewModel(
    browserViewModel: BrowserViewModel,
) : ViewModel() {
    val settingsUiState: StateFlow<SettingsUiState?> = browserViewModel.settingsUiState

    suspend fun recordHistory(url: String, title: String): Long {
        return browserViewModel.recordHistory(url, title)
    }

    suspend fun updateHistoryTitle(id: Long, title: String) {
        browserViewModel.updateHistoryTitle(id, title)
    }

    fun searchHistory(query: String): Flow<List<HistoryEntry>> {
        return browserViewModel.searchHistory(query)
    }
}

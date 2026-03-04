package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

internal class BrowserScreenViewModel(
    private val browserViewModel: BrowserViewModel,
) : ViewModel() {
    val settingsUiState: StateFlow<SettingsUiState?> = browserViewModel.settingsUiState

    fun bumpTabPersistence() {
        browserViewModel.bumpTabPersistence()
    }
}

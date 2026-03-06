package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.SettingsUiState

internal class BrowserScreenViewModel(
    browserViewModel: BrowserViewModel,
) : ViewModel() {
    val settingsUiState: StateFlow<SettingsUiState?> = browserViewModel.settingsUiState
}

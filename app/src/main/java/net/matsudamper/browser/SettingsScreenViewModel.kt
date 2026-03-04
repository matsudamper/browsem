package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider

internal class SettingsScreenViewModel(
    private val browserViewModel: BrowserViewModel,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState?> = browserViewModel.settingsUiState

    fun setHomepageType(type: HomepageType) {
        browserViewModel.setHomepageType(type)
    }

    fun setCustomHomepageUrl(url: String) {
        browserViewModel.setCustomHomepageUrl(url)
    }

    fun setSearchProvider(provider: SearchProvider) {
        browserViewModel.setSearchProvider(provider)
    }

    fun setCustomSearchUrl(url: String) {
        browserViewModel.setCustomSearchUrl(url)
    }

    fun setThemeMode(mode: ThemeMode) {
        browserViewModel.setThemeMode(mode)
    }

    fun setTranslationProvider(provider: TranslationProvider) {
        browserViewModel.setTranslationProvider(provider)
    }

    fun setEnableThirdPartyCa(enabled: Boolean) {
        browserViewModel.setEnableThirdPartyCa(enabled)
    }
}

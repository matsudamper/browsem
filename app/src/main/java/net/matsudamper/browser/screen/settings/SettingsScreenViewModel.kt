package net.matsudamper.browser.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.matsudamper.browser.SettingsUiState
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider

internal class SettingsScreenViewModel(
    private val settingsRepository: SettingsRepository,
    val uiState: StateFlow<SettingsUiState?>,
) : ViewModel() {

    fun setHomepageType(type: HomepageType) {
        viewModelScope.launch { settingsRepository.setHomepageType(type) }
    }

    fun setCustomHomepageUrl(url: String) {
        viewModelScope.launch { settingsRepository.setCustomHomepageUrl(url) }
    }

    fun setSearchProvider(provider: SearchProvider) {
        viewModelScope.launch { settingsRepository.setSearchProvider(provider) }
    }

    fun setCustomSearchUrl(url: String) {
        viewModelScope.launch { settingsRepository.setCustomSearchUrl(url) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setTranslationProvider(provider: TranslationProvider) {
        viewModelScope.launch { settingsRepository.setTranslationProvider(provider) }
    }

    fun setEnableThirdPartyCa(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableThirdPartyCa(enabled) }
    }

    fun setEnableWebSuggestions(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableWebSuggestions(enabled) }
    }
}

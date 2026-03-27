package net.matsudamper.browser.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.SettingsUiState
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider

internal class SettingsScreenViewModel(
    private val settingsRepository: SettingsRepository,
    settingsUiState: StateFlow<SettingsUiState?>,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : SettingsScreenUiState.Callbacks {
        override fun setHomepageType(type: HomepageType) {
            viewModelScope.launch { settingsRepository.setHomepageType(type) }
        }

        override fun setCustomHomepageUrl(url: String) {
            viewModelScope.launch { settingsRepository.setCustomHomepageUrl(url) }
        }

        override fun setSearchProvider(provider: SearchProvider) {
            viewModelScope.launch { settingsRepository.setSearchProvider(provider) }
        }

        override fun setCustomSearchUrl(url: String) {
            viewModelScope.launch { settingsRepository.setCustomSearchUrl(url) }
        }

        override fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { settingsRepository.setThemeMode(mode) }
        }

        override fun setTranslationProvider(provider: TranslationProvider) {
            viewModelScope.launch { settingsRepository.setTranslationProvider(provider) }
        }

        override fun setEnableThirdPartyCa(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setEnableThirdPartyCa(enabled) }
        }

        override fun setEnableWebSuggestions(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setEnableWebSuggestions(enabled) }
        }
    }

    val uiState: StateFlow<SettingsScreenUiState?> = MutableStateFlow<SettingsScreenUiState?>(null)
        .also { uiStateFlow ->
            viewModelScope.launch {
                settingsUiState.collectLatest { settings ->
                    uiStateFlow.update {
                        settings?.let {
                            SettingsScreenUiState(
                                callbacks = callbacks,
                                homepageType = it.homepageType,
                                customHomepageUrl = it.customHomepageUrl,
                                searchProvider = it.searchProvider,
                                customSearchUrl = it.customSearchUrl,
                                themeMode = it.themeMode,
                                translationProvider = it.translationProvider,
                                enableThirdPartyCa = it.enableThirdPartyCa,
                                enableWebSuggestions = it.enableWebSuggestions,
                            )
                        }
                    }
                }
            }
        }.asStateFlow()

    interface Event
}

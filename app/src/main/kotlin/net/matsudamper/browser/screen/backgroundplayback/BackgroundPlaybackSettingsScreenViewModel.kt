package net.matsudamper.browser.screen.backgroundplayback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.resolvedBackgroundPlaybackDomains
import net.matsudamper.browser.ui.settings.BackgroundPlaybackSettingsScreenUiState

internal class BackgroundPlaybackSettingsScreenViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val callbacks = object : BackgroundPlaybackSettingsScreenUiState.Callbacks {
        override fun addDomain(domain: String) {
            viewModelScope.launch { settingsRepository.addBackgroundPlaybackDomain(domain) }
        }

        override fun removeDomain(domain: String) {
            viewModelScope.launch { settingsRepository.removeBackgroundPlaybackDomain(domain) }
        }

        override fun resetToDefaults() {
            viewModelScope.launch { settingsRepository.resetBackgroundPlaybackDomains() }
        }
    }

    val uiState: StateFlow<BackgroundPlaybackSettingsScreenUiState> = MutableStateFlow(
        BackgroundPlaybackSettingsScreenUiState(
            callbacks = callbacks,
            allowedDomains = emptyList(),
        )
    ).also { uiStateFlow ->
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                uiStateFlow.update {
                    BackgroundPlaybackSettingsScreenUiState(
                        callbacks = callbacks,
                        allowedDomains = settings.resolvedBackgroundPlaybackDomains(),
                    )
                }
            }
        }
    }.asStateFlow()
}

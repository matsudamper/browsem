package net.matsudamper.browser.screen.sitesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.ui.settings.SiteSettingsScreenUiState

internal class SiteSettingsScreenViewModel(
    private val host: String,
    private val siteSettingsRepository: SiteSettingsRepository,
) : ViewModel() {

    private val callbacks = object : SiteSettingsScreenUiState.Callbacks {
        override fun setMicrophonePermission(state: SitePermissionState) {
            viewModelScope.launch {
                siteSettingsRepository.setMicrophonePermission(host, state)
            }
        }

        override fun setGeolocationState(state: SiteGeolocationState) {
            viewModelScope.launch {
                siteSettingsRepository.setGeolocationState(host, state)
            }
        }
    }

    val uiState: StateFlow<SiteSettingsScreenUiState> = MutableStateFlow(
        SiteSettingsScreenUiState(
            callbacks = callbacks,
            host = host,
            microphonePermission = null,
            geolocationState = null,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            siteSettingsRepository.requestedMicrophonePermission(host).collectLatest { permission ->
                uiStateFlow.update { it.copy(microphonePermission = permission) }
            }
        }
        viewModelScope.launch {
            siteSettingsRepository.requestedGeolocationState(host).collectLatest { state ->
                uiStateFlow.update { it.copy(geolocationState = state) }
            }
        }
    }.asStateFlow()
}

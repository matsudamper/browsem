package net.matsudamper.browser.screen.sitesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
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

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        /** OS の位置情報権限を要求する。結果は onLocationPermissionResult で受け取る */
        fun onRequestLocationPermission()
    }

    private val callbacks = object : SiteSettingsScreenUiState.Callbacks {
        override fun setMicrophonePermission(state: SitePermissionState) {
            viewModelScope.launch {
                siteSettingsRepository.setMicrophonePermission(host, state)
            }
        }

        override fun setGeolocationState(state: SiteGeolocationState) {
            // 実際の位置情報は OS の位置情報権限が必要なため、ここでは保存せず権限要求を行い、
            // 許可された場合のみ onLocationPermissionResult で保存する
            if (state == SiteGeolocationState.SITE_GEOLOCATION_REAL) {
                eventHandler.trySend { it.onRequestLocationPermission() }
                return
            }
            viewModelScope.launch {
                siteSettingsRepository.setGeolocationState(host, state)
            }
        }
    }

    /** OS の位置情報権限要求の結果。許可された場合のみ「実際の位置情報」を保存する */
    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) return
        viewModelScope.launch {
            siteSettingsRepository.setGeolocationState(
                host = host,
                state = SiteGeolocationState.SITE_GEOLOCATION_REAL,
            )
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

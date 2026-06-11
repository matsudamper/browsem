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
import net.matsudamper.browser.awaitGecko
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.ui.settings.SiteSettingsScreenUiState
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController

internal class SiteSettingsScreenViewModel(
    private val host: String,
    private val siteSettingsRepository: SiteSettingsRepository,
    private val geckoRuntime: GeckoRuntime,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        /** OS の位置情報権限を要求する。結果は onLocationPermissionResult で受け取る */
        fun onRequestLocationPermission()
    }

    // 権限要求の多重発行を防ぐ in-flight フラグ
    private var isLocationPermissionRequestInFlight = false

    private val callbacks = object : SiteSettingsScreenUiState.Callbacks {
        override fun setMicrophonePermission(state: SitePermissionState) {
            viewModelScope.launch {
                siteSettingsRepository.setMicrophonePermission(host, state)
            }
        }

        override fun requestClearData(type: SiteSettingsScreenUiState.ClearDataType) {
            uiStateFlow.update { it.copy(clearDataConfirmDialog = type) }
        }

        override fun confirmClearData() {
            val type = uiStateFlow.value.clearDataConfirmDialog ?: return
            uiStateFlow.update { it.copy(clearDataConfirmDialog = null) }
            viewModelScope.launch {
                clearData(type)
            }
        }

        override fun dismissClearDataConfirm() {
            uiStateFlow.update { it.copy(clearDataConfirmDialog = null) }
        }

        override fun consumeClearDataResultMessage() {
            uiStateFlow.update { it.copy(clearDataResultMessage = null) }
        }

        override fun setGeolocationState(state: SiteGeolocationState) {
            // 実際の位置情報は OS の位置情報権限が必要なため、ここでは保存せず権限要求を行い、
            // 許可された場合のみ onLocationPermissionResult で保存する
            if (state == SiteGeolocationState.SITE_GEOLOCATION_REAL) {
                if (isLocationPermissionRequestInFlight) return
                isLocationPermissionRequestInFlight = true
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
        isLocationPermissionRequestInFlight = false
        if (!granted) return
        viewModelScope.launch {
            siteSettingsRepository.setGeolocationState(
                host = host,
                state = SiteGeolocationState.SITE_GEOLOCATION_REAL,
            )
        }
    }

    private val uiStateFlow: MutableStateFlow<SiteSettingsScreenUiState> = MutableStateFlow(
        SiteSettingsScreenUiState(
            callbacks = callbacks,
            host = host,
            microphonePermission = null,
            geolocationState = null,
            clearDataConfirmDialog = null,
            clearDataResultMessage = null,
        ),
    )

    val uiState: StateFlow<SiteSettingsScreenUiState> = uiStateFlow.also {
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

    private suspend fun clearData(type: SiteSettingsScreenUiState.ClearDataType) {
        val flags = when (type) {
            SiteSettingsScreenUiState.ClearDataType.Cookie -> StorageController.ClearFlags.COOKIES
            SiteSettingsScreenUiState.ClearDataType.Cache -> StorageController.ClearFlags.ALL_CACHES
        }
        val targetName = when (type) {
            SiteSettingsScreenUiState.ClearDataType.Cookie -> "Cookie"
            SiteSettingsScreenUiState.ClearDataType.Cache -> "キャッシュ"
        }
        val message = runCatching {
            geckoRuntime.storageController.clearDataFromHost(host, flags).awaitGecko()
        }.fold(
            onSuccess = { "${targetName}を削除しました" },
            onFailure = { "${targetName}の削除に失敗しました" },
        )
        uiStateFlow.update { it.copy(clearDataResultMessage = message) }
    }
}

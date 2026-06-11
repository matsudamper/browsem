package net.matsudamper.browser.screen.sitesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.awaitGecko
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
    }

    private val uiStateFlow: MutableStateFlow<SiteSettingsScreenUiState> = MutableStateFlow(
        SiteSettingsScreenUiState(
            callbacks = callbacks,
            host = host,
            microphonePermission = null,
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

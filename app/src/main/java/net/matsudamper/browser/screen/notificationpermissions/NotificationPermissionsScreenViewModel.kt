package net.matsudamper.browser.screen.notificationpermissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.SettingsUiState
import net.matsudamper.browser.data.SettingsRepository

internal class NotificationPermissionsScreenViewModel(
    private val settingsRepository: SettingsRepository,
    settingsUiState: StateFlow<SettingsUiState?>,
) : ViewModel() {
    val allowedOrigins: StateFlow<List<String>> = settingsUiState
        .map { uiState -> uiState?.notificationAllowedOrigins ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeNotificationAllowedOrigin(origin: String) {
        viewModelScope.launch { settingsRepository.removeNotificationAllowedOrigin(origin) }
    }
}

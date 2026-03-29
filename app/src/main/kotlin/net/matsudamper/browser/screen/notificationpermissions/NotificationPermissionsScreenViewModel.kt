package net.matsudamper.browser.screen.notificationpermissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.ui.notifications.NotificationPermissionsScreenUiState

internal class NotificationPermissionsScreenViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val callbacks = object : NotificationPermissionsScreenUiState.Callbacks {
        override fun removeNotificationAllowedOrigin(origin: String) {
            viewModelScope.launch { settingsRepository.removeNotificationAllowedOrigin(origin) }
        }
    }

    val uiState: StateFlow<NotificationPermissionsScreenUiState> = MutableStateFlow(
        NotificationPermissionsScreenUiState(
            callbacks = callbacks,
            allowedOrigins = emptyList(),
        )
    ).also { uiStateFlow ->
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                uiStateFlow.update {
                    NotificationPermissionsScreenUiState(
                        callbacks = callbacks,
                        allowedOrigins = settings.notificationAllowedOriginsList,
                    )
                }
            }
        }
    }.asStateFlow()
}

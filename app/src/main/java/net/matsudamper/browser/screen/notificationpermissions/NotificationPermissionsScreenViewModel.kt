package net.matsudamper.browser.screen.notificationpermissions

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
import net.matsudamper.browser.data.SettingsRepository

internal class NotificationPermissionsScreenViewModel(
    private val settingsRepository: SettingsRepository,
    settingsUiState: StateFlow<SettingsUiState?>,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

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
            settingsUiState.collectLatest { settings ->
                uiStateFlow.update {
                    NotificationPermissionsScreenUiState(
                        callbacks = callbacks,
                        allowedOrigins = settings?.notificationAllowedOrigins ?: emptyList(),
                    )
                }
            }
        }
    }.asStateFlow()

    interface Event
}

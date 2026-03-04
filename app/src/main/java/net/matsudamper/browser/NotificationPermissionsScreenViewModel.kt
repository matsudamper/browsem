package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class NotificationPermissionsScreenViewModel(
    private val browserViewModel: BrowserViewModel,
) : ViewModel() {
    val allowedOrigins: StateFlow<List<String>> = browserViewModel.settingsUiState
        .map { uiState -> uiState?.notificationAllowedOrigins ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeNotificationAllowedOrigin(origin: String) {
        browserViewModel.removeNotificationAllowedOrigin(origin)
    }
}

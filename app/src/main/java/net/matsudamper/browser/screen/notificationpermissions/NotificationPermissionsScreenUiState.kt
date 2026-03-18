package net.matsudamper.browser.screen.notificationpermissions

internal data class NotificationPermissionsScreenUiState(
    val callbacks: Callbacks,
    val allowedOrigins: List<String>,
) {
    interface Callbacks {
        fun removeNotificationAllowedOrigin(origin: String)
    }
}

package net.matsudamper.browser.ui.notifications

data class NotificationPermissionsScreenUiState(
    val callbacks: Callbacks,
    val allowedOrigins: List<String>,
) {
    interface Callbacks {
        fun removeNotificationAllowedOrigin(origin: String)
    }
}

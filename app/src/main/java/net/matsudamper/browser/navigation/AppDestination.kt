package net.matsudamper.browser.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppDestination : NavKey, java.io.Serializable {
    @Serializable
    data object Setup : NavKey, java.io.Serializable

    @Serializable
    data class Browser(val tabId: String, val beforeTab: Browser?) : AppDestination, java.io.Serializable

    @Serializable
    data object Settings : AppDestination, java.io.Serializable

    @Serializable
    data object Extensions : AppDestination, java.io.Serializable

    @Serializable
    data object NotificationPermissions : AppDestination, java.io.Serializable

    @Serializable
    data object Tabs : AppDestination, java.io.Serializable
}
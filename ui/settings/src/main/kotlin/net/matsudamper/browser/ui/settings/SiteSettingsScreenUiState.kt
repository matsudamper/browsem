package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.SitePermissionState

@Stable
data class SiteSettingsScreenUiState(
    val callbacks: Callbacks,
    val host: String,
    val microphonePermission: SitePermissionState,
) {
    interface Callbacks {
        fun setMicrophonePermission(state: SitePermissionState)
    }
}

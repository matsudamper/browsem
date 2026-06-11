package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.SitePermissionState

@Stable
data class SiteSettingsScreenUiState(
    val callbacks: Callbacks,
    val host: String,
    /** マイク権限の状態。サイトから一度も要求されていない場合は null で、項目を表示しない */
    val microphonePermission: SitePermissionState?,
) {
    interface Callbacks {
        fun setMicrophonePermission(state: SitePermissionState)
    }
}

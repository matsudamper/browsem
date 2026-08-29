package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.crashlog.CrashLogEntity

@Stable
data class CrashLogDetailScreenUiState(
    val callbacks: Callbacks,
    val entry: CrashLogEntity?,
) {
    interface Callbacks {
        fun onClickCopyBody()
    }
}

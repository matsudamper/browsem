package net.matsudamper.browser.ui.settings.crash

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.crashlog.CrashLogEntity

@Stable
data class CrashLogDetailScreenUiState(
    val callbacks: Callbacks,
    val isLoading: Boolean,
    val entry: CrashLogEntity?,
) {
    interface Callbacks {
        fun onClickCopyBody()
    }
}

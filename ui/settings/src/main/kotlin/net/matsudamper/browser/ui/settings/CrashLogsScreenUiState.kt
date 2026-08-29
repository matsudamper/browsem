package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.crashlog.CrashLogEntity

@Stable
data class CrashLogsScreenUiState(
    val callbacks: Callbacks,
    val entries: List<CrashLogEntity>,
    val showDeleteAllDialog: Boolean,
) {
    interface Callbacks {
        fun onClickEntry(id: Long)
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.crashlog.CrashLogListItem

@Stable
data class CrashLogsScreenUiState(
    val callbacks: Callbacks,
    val isLoading: Boolean,
    val entries: List<CrashLogListItem>,
    val showDeleteAllDialog: Boolean,
) {
    interface Callbacks {
        fun onClickEntry(id: Long)
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

package net.matsudamper.browser.ui.settings.crash

import androidx.compose.runtime.Stable

@Stable
data class CrashLogsScreenUiState(
    val callbacks: Callbacks,
    val isLoading: Boolean,
    val entries: List<EntryItem>,
    val showDeleteAllDialog: Boolean,
) {
    @Stable
    data class EntryItem(
        val id: Long,
        val title: String,
        val occurredAt: Long,
        val onClick: () -> Unit,
    )

    interface Callbacks {
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

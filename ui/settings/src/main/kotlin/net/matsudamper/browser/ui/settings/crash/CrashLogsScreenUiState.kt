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
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
        }
    }

    interface Callbacks {
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

internal object PreviewCrashLogEntryListener : CrashLogsScreenUiState.EntryItem.Listener {
    override fun onClick() = Unit
}

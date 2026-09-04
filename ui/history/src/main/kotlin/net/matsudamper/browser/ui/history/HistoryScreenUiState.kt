package net.matsudamper.browser.ui.history

import androidx.compose.runtime.Stable

@Stable
data class HistoryScreenUiState(
    val callbacks: Callbacks,
    val searchQuery: String,
    val entryList: EntryList?,
    val showDeleteAllDialog: Boolean,
) {
    @Stable
    data class EntryList(
        val searchQuery: String,
        val entries: List<EntryItem>,
    )

    @Stable
    data class EntryItem(
        val id: Long,
        val title: String,
        val url: String,
        val visitedAt: Long,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
            fun onDelete()
        }
    }

    interface Callbacks {
        fun onSearchQueryChange(query: String)
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

internal object PreviewHistoryEntryListener : HistoryScreenUiState.EntryItem.Listener {
    override fun onClick() = Unit
    override fun onDelete() = Unit
}

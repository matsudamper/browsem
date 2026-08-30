package net.matsudamper.browser.ui.history

import androidx.compose.runtime.Stable

@Stable
data class HistoryScreenUiState(
    val callbacks: Callbacks,
    val searchQuery: String,
    val entries: List<EntryItem>,
    val showDeleteAllDialog: Boolean,
) {
    @Stable
    data class EntryItem(
        val id: Long,
        val title: String,
        val url: String,
        val visitedAt: Long,
        val onClick: () -> Unit,
        val onDelete: () -> Unit,
    )

    interface Callbacks {
        fun onSearchQueryChange(query: String)
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

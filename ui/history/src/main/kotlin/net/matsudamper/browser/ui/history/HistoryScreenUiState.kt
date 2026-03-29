package net.matsudamper.browser.ui.history

import net.matsudamper.browser.data.history.HistoryEntry

data class HistoryScreenUiState(
    val callbacks: Callbacks,
    val searchQuery: String,
    val entries: List<HistoryEntry>,
    val showDeleteAllDialog: Boolean,
) {
    interface Callbacks {
        fun onSearchQueryChange(query: String)
        fun onClickEntry(url: String)
        fun onDeleteEntry(id: Long)
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

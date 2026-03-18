package net.matsudamper.browser.screen.history

import net.matsudamper.browser.data.history.HistoryEntry

internal data class HistoryScreenUiState(
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

package net.matsudamper.browser.screen.browser

data class BrowserScreenUiState(
    val urlBarSuggestions: UrlBarSuggestionsUiState,
    val callbacks: Callbacks,
) {
    interface Callbacks {
        suspend fun onHistoryRecord(url: String, title: String): Long
        suspend fun onHistoryTitleUpdate(id: Long, title: String)
        fun onUrlInputChanged(query: String)
    }
}

package net.matsudamper.browser.screen.browser

import net.matsudamper.browser.BrowserTab

data class BrowserScreenUiState(
    val urlBarSuggestions: UrlBarSuggestionsUiState,
    val orderedBrowserTabs: List<BrowserTab> = emptyList(),
    val callbacks: Callbacks,
) {
    interface Callbacks {
        suspend fun onHistoryRecord(url: String, title: String): Long
        suspend fun onHistoryTitleUpdate(id: Long, title: String)
        fun onUrlInputChanged(query: String)
    }
}

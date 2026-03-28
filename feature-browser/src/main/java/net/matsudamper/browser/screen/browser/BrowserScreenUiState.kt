package net.matsudamper.browser.screen.browser

import net.matsudamper.browser.BrowserTab

data class BrowserScreenUiState(
    val urlBarSuggestions: UrlBarSuggestionsUiState,
    val swipePreview: SwipePreviewUiState = SwipePreviewUiState(),
    val callbacks: Callbacks,
) {
    data class SwipePreviewUiState(
        val previousTab: BrowserTab? = null,
        val nextTab: BrowserTab? = null,
    )

    interface Callbacks {
        suspend fun onHistoryRecord(url: String, title: String): Long
        suspend fun onHistoryTitleUpdate(id: Long, title: String)
        fun onUrlInputChanged(query: String)
    }
}

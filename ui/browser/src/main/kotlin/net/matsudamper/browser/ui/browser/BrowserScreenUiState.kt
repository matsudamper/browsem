package net.matsudamper.browser.ui.browser

import androidx.compose.runtime.Stable
import net.matsudamper.browser.BrowserTab

@Stable
data class BrowserScreenUiState(
    val urlBarSuggestions: UrlBarSuggestionsUiState,
    val swipePreview: SwipePreviewUiState = SwipePreviewUiState(),
    val groupTabCount: Int?,
    val callbacks: Callbacks,
) {
    @Stable
    data class SwipePreviewUiState(
        val previousTab: AdjacentTabPreview? = null,
        val nextTab: AdjacentTabPreview? = null,
        val onBackToOpener: (() -> Unit)? = null,
    )

    @Stable
    data class AdjacentTabPreview(
        val tab: BrowserTab,
        val onSelect: () -> Unit,
    )

    interface Callbacks {
        suspend fun onHistoryRecord(url: String, title: String): Long
        suspend fun onHistoryTitleUpdate(id: Long, title: String)
        fun onUrlInputChanged(query: String)
    }
}

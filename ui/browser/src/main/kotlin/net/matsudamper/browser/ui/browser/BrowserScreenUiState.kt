package net.matsudamper.browser.ui.browser

import androidx.compose.runtime.Stable
import net.matsudamper.browser.BrowserTab

@Stable
data class BrowserScreenUiState(
    val urlBarSuggestions: UrlBarSuggestionsUiState,
    val swipePreview: SwipePreviewUiState = SwipePreviewUiState(),
    val groupTabCount: Int?,
    val externalDownloadDialogListener: ExternalDownloadDialogListener?,
    val externalTabInitialUrl: String?,
    val callbacks: Callbacks,
) {
    @Stable
    interface ExternalDownloadDialogListener {
        fun onResolved()
    }

    @Stable
    data class SwipePreviewUiState(
        val previousTab: AdjacentTabPreview? = null,
        val nextTab: AdjacentTabPreview? = null,
        val backToOpenerListener: BackToOpenerListener? = null,
    ) {
        @Stable
        interface BackToOpenerListener {
            fun onBackToOpener()
        }
    }

    @Stable
    data class AdjacentTabPreview(
        val tab: BrowserTab,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onSelect()
        }
    }

    interface Callbacks {
        suspend fun onHistoryRecord(url: String, title: String): Long
        suspend fun onHistoryTitleUpdate(id: Long, title: String)
        fun onUrlInputChanged(query: String)
    }
}

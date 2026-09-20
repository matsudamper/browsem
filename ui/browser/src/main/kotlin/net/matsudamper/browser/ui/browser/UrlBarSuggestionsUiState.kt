package net.matsudamper.browser.ui.browser

import androidx.compose.runtime.Stable

data class UrlBarSuggestionsUiState(
    val historySuggestions: List<HistorySuggestion> = listOf(),
    val webSuggestions: List<String> = listOf(),
    val isLoadingWebSuggestions: Boolean = false,
) {
    @Stable
    data class HistorySuggestion(
        val id: Long,
        val url: String,
        val title: String,
    )
}

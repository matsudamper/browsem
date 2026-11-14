package net.matsudamper.browser.ui.browser

data class UrlBarSuggestionsUiState(
    val historySuggestions: List<HistorySuggestion>,
    val webSuggestions: List<String>,
    val isLoadingWebSuggestions: Boolean,
) {
    data class HistorySuggestion(
        val id: Long,
        val url: String,
        val title: String,
    )
}

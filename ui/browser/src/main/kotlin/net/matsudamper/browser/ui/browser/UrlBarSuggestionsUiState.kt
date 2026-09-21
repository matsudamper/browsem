package net.matsudamper.browser.ui.browser

data class UrlBarSuggestionsUiState(
    val historySuggestions: List<HistorySuggestion> = listOf(),
    val webSuggestions: List<String> = listOf(),
    val isLoadingWebSuggestions: Boolean = false,
) {
    data class HistorySuggestion(
        val id: Long,
        val url: String,
        val title: String,
    )
}

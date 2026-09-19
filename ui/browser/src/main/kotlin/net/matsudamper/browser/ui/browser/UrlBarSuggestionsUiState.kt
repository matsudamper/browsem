package net.matsudamper.browser.ui.browser

import net.matsudamper.browser.data.history.HistoryEntry

data class UrlBarSuggestionsUiState(
    val historySuggestions: List<HistoryEntry> = listOf(),
    val webSuggestions: List<String> = listOf(),
    val isLoadingWebSuggestions: Boolean = false,
)

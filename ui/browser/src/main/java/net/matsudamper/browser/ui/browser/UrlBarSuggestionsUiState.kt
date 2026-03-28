package net.matsudamper.browser.ui.browser

import net.matsudamper.browser.data.history.HistoryEntry

data class UrlBarSuggestionsUiState(
    val historySuggestions: List<HistoryEntry> = emptyList(),
    val webSuggestions: List<String> = emptyList(),
    val isLoadingWebSuggestions: Boolean = false,
)

package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.ui.browser.BrowserScreenUiState
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState

class CustomTabScreenViewModel(
    historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
    webSuggestionRepository: WebSuggestionRepository,
) : ViewModel() {
    private val urlBarSuggestionsStateOwner = UrlBarSuggestionsStateOwner(
        scope = viewModelScope,
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
        webSuggestionRepository = webSuggestionRepository,
    )
    private val callbacks = urlBarSuggestionsStateOwner.callbacks

    val uiState: StateFlow<BrowserScreenUiState> = MutableStateFlow(
        BrowserScreenUiState(
            urlBarSuggestions = UrlBarSuggestionsUiState(),
            groupTabCount = null,
            externalDownloadDialogListener = null,
            externalTabInitialUrl = null,
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            urlBarSuggestionsStateOwner.urlBarSuggestions.collectLatest { suggestions ->
                uiStateFlow.update {
                    BrowserScreenUiState(
                        urlBarSuggestions = suggestions,
                        groupTabCount = null,
                        externalDownloadDialogListener = null,
                        externalTabInitialUrl = null,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }.asStateFlow()
}

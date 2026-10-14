package net.matsudamper.browser.screen.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.WebAppRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.ui.browser.BrowserScreenUiState
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState

class CustomTabScreenViewModel(
    historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
    webSuggestionRepository: WebSuggestionRepository,
    webAppRepository: WebAppRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {
    private val urlBarSuggestionsStateOwner = UrlBarSuggestionsStateOwner(
        scope = viewModelScope,
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
        webSuggestionRepository = webSuggestionRepository,
        webAppRepository = webAppRepository,
        // カスタムタブは有効プロファイルの contextId で開く。履歴サジェストもそれに合わせる
        activeProfileIdFlow = profileRepository.observeProfiles().map { profiles ->
            profiles.firstOrNull { it.isActive }?.id ?: ProfileId.DEFAULT
        },
    )
    private val callbacks = urlBarSuggestionsStateOwner.callbacks

    val uiState: StateFlow<BrowserScreenUiState> = MutableStateFlow(
        BrowserScreenUiState(
            urlBarSuggestions = UrlBarSuggestionsUiState(),
            swipePreview = BrowserScreenUiState.SwipePreviewUiState(),
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
                        swipePreview = BrowserScreenUiState.SwipePreviewUiState(),
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

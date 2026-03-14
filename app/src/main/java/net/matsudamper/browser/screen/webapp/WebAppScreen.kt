package net.matsudamper.browser.screen.webapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.matsudamper.browser.BrowserRuntimeCoordinator
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.GeckoBrowserTab
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun WebAppScreen(
    initialUrl: String,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    browserSessionController: BrowserSessionController,
    settingsRepository: SettingsRepository,
    historyRepository: HistoryRepository,
    webSuggestionRepository: WebSuggestionRepository,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
) {
    val viewModel = viewModel(initializer = {
        BrowserScreenViewModel(
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            webSuggestionRepository = webSuggestionRepository,
        )
    })
    val urlBarSuggestions by viewModel.urlBarSuggestions.collectAsState()
    val browserTab = remember(browserSessionController, initialUrl) {
        browserSessionController.createAndAppendTab(initialUrl = initialUrl)
    }

    GeckoBrowserTab(
        modifier = Modifier.fillMaxSize(),
        browserTab = browserTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
        translationProvider = translationProvider,
        themeColorExtension = themeColorExtension,
        mediaWebExtension = mediaWebExtension,
        browserSessionController = browserSessionController,
        tabCount = 1,
        onInstallExtensionRequest = {},
        onDesktopNotificationPermissionRequest = { _ ->
            onDesktopNotificationPermissionRequest()
        },
        onOpenSettings = {},
        onOpenTabs = {},
        enableTabUi = false,
        showInstallExtensionItem = false,
        // バックナビゲーションを有効にしてブラウザ履歴を遡れるようにする
        enableBackNavigation = true,
        // ウェブアプリモード: 閉じるボタンなし、カスタムタブ風のツールバー
        webAppMode = true,
        onOpenNewSessionRequest = { uri ->
            browserTab.session.loadUri(uri)
            browserTab.session
        },
        onHistoryRecord = { url, title -> historyRepository.recordVisit(url, title) },
        onHistoryTitleUpdate = { id, title -> historyRepository.updateTitle(id, title) },
        urlBarSuggestions = urlBarSuggestions,
        onUrlInputChanged = viewModel::onUrlInputChanged,
    )
}

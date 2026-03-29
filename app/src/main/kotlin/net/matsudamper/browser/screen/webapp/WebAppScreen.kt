package net.matsudamper.browser.screen.webapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.runBlocking
import net.matsudamper.browser.BrowserSessionLifecycleController
import net.matsudamper.browser.BrowserTabController
import net.matsudamper.browser.GeckoBrowserTab
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.screen.browser.WebAppScreenViewModel
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun WebAppScreen(
    initialUrl: String,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    settingsRepository: SettingsRepository,
    historyRepository: HistoryRepository,
    webSuggestionRepository: WebSuggestionRepository,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    onRequestDownloadNotificationPermission: () -> Unit,
) {
    val viewModel = viewModel(initializer = {
        WebAppScreenViewModel(
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            webSuggestionRepository = webSuggestionRepository,
        )
    })
    val uiState by viewModel.uiState.collectAsState()
    val browserTab = remember(browserTabController, initialUrl) {
        // TODO runBlocking使わない
        runBlocking {
            browserTabController.createAndAppendTab(initialUrl = initialUrl)
        }
    }
    DisposableEffect(browserTabController, browserTab.tabId) {
        onDispose {
            browserTabController.closeTab(browserTab.tabId)
        }
    }

    GeckoBrowserTab(
        modifier = Modifier.fillMaxSize(),
        browserTab = browserTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
        translationProvider = translationProvider,
        themeColorExtension = themeColorExtension,
        mediaWebExtension = mediaWebExtension,
        browserSessionLifecycleController = browserSessionLifecycleController,
        tabCount = 1,
        onInstallExtensionRequest = {},
        onDesktopNotificationPermissionRequest = { _ ->
            onDesktopNotificationPermissionRequest()
        },
        onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
        onOpenSettings = {},
        onOpenTabs = {},
        enableTabUi = false,
        showInstallExtensionItem = false,
        // バックナビゲーションを有効にしてブラウザ履歴を遡れるようにする
        enableBackNavigation = true,
        // ウェブアプリモード: 閉じるボタンなし、カスタムタブ風のツールバー
        webAppMode = true,
        onOpenNewSessionRequest = { uri ->
            // ウェブアプリモードでは新規タブを作成せず、同じセッションでURLを読み込む
            // 新規タブを作成するとユーザーから見えないタブになるため
            browserTab.session.loadUri(uri)
            browserTab.session
        },
        onHistoryRecord = uiState.callbacks::onHistoryRecord,
        onHistoryTitleUpdate = uiState.callbacks::onHistoryTitleUpdate,
        urlBarSuggestions = uiState.urlBarSuggestions,
        onUrlInputChanged = uiState.callbacks::onUrlInputChanged,
    )
}

package net.matsudamper.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CancellationException

/**
 * ホームに「アプリとして追加」された場合に起動するActivity。
 * カスタムタブに近い外観だが、閉じるボタンはなく、バックボタンでブラウザ履歴を遡る。
 * 独立したタスクとして管理され、アプリの履歴（最近使ったアプリ）に残る。
 */
class WebAppActivity : ComponentActivity() {
    private val runtime: GeckoRuntime by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val historyRepository: HistoryRepository by inject()
    private val webSuggestionRepository: WebSuggestionRepository by inject()

    private lateinit var runtimeCoordinator: BrowserRuntimeCoordinator

    private var pendingNotificationPermissionResult: GeckoResult<Int>? = null

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val pendingResult = pendingNotificationPermissionResult ?: return@registerForActivityResult
        pendingNotificationPermissionResult = null
        pendingResult.complete(
            if (isGranted) {
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            } else {
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.settings.setExtensionsWebAPIEnabled(true)

        runtimeCoordinator = BrowserRuntimeCoordinator(applicationContext, runtime)

        val initialUrl = intent.dataString.orEmpty()
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtimeCoordinator.applyRuntimeSettings(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                WebAppScreen(
                    initialUrl = initialUrl.takeIf { it.isNotBlank() } ?: browserSettings.resolvedHomepageUrl(),
                    homepageUrl = browserSettings.resolvedHomepageUrl(),
                    searchTemplate = browserSettings.resolvedSearchTemplate(),
                    translationProvider = browserSettings.translationProvider,
                    browserSessionController = runtimeCoordinator.browserSessionController,
                    settingsRepository = settingsRepository,
                    historyRepository = historyRepository,
                    webSuggestionRepository = webSuggestionRepository,
                    themeColorExtension = runtimeCoordinator.themeColorExtension,
                    mediaWebExtension = runtimeCoordinator.mediaWebExtension,
                    onDesktopNotificationPermissionRequest = { requestNotificationPermissionIfNeeded() },
                )
            }
        }
    }

    override fun onDestroy() {
        pendingNotificationPermissionResult?.completeExceptionally(
            CancellationException("Activity was destroyed before notification permission completed.")
        )
        pendingNotificationPermissionResult = null
        if (::runtimeCoordinator.isInitialized) {
            runtimeCoordinator.close()
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded(): GeckoResult<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
        }
        pendingNotificationPermissionResult?.let {
            return GeckoResult.fromException(
                IllegalStateException("Another notification permission request is already pending.")
            )
        }

        return GeckoResult<Int>().also { result ->
            pendingNotificationPermissionResult = result
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun WebAppScreen(
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

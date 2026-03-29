package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsSessionToken
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.screen.browser.CustomTabScreenViewModel
import net.matsudamper.browser.ui.common.BrowserTheme
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CancellationException

class CustomTabActivity : ComponentActivity() {
    private val runtime: GeckoRuntime by inject()
    private val themeColorExtension: ThemeColorWebExtension by inject()
    private val mediaWebExtensionInstance: MediaWebExtension by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val tabRepository: TabRepository by inject()
    private val historyRepository: HistoryRepository by inject()
    private val webSuggestionRepository: WebSuggestionRepository by inject()

    private lateinit var browserTabController: BrowserTabController
    private lateinit var browserSessionLifecycleController: BrowserSessionLifecycleController

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
        enableEdgeToEdge()
        runtime.settings.setExtensionsWebAPIEnabled(true)

        // 拡張機能は Koin の single で管理されるため、ここではセッション管理のみ担当する
        browserTabController = BrowserTabController(tabRepository)
        browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

        val initialUrl = intent.dataString.orEmpty()
        val customTabsSessionToken = CustomTabsSessionToken.getSessionTokenFromIntent(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtime.settings.setEnterpriseRootsEnabled(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                Box(
                    modifier = Modifier.semantics {
                        testTagsAsResourceId = true
                    },
                ) {
                CustomTabScreen(
                    initialUrl = initialUrl.takeIf { it.isNotBlank() } ?: browserSettings.resolvedHomepageUrl(),
                    customTabsSessionToken = customTabsSessionToken,
                    homepageUrl = browserSettings.resolvedHomepageUrl(),
                    searchTemplate = browserSettings.resolvedSearchTemplate(),
                    translationProvider = browserSettings.translationProvider,
                    browserTabController = browserTabController,
                    browserSessionLifecycleController = browserSessionLifecycleController,
                    settingsRepository = settingsRepository,
                    historyRepository = historyRepository,
                    webSuggestionRepository = webSuggestionRepository,
                    themeColorExtension = themeColorExtension,
                    mediaWebExtension = mediaWebExtensionInstance,
                    onClose = ::finish,
                    onOpenInBrowser = ::openInMainBrowser,
                    onDesktopNotificationPermissionRequest = { requestNotificationPermissionIfNeeded() },
                    onRequestDownloadNotificationPermission = { requestDownloadNotificationPermission() },
                )
                }
            }
        }
    }

    override fun onDestroy() {
        pendingNotificationPermissionResult?.completeExceptionally(
            CancellationException("Activity was destroyed before notification permission completed.")
        )
        pendingNotificationPermissionResult = null
        if (::browserTabController.isInitialized) {
            browserTabController.close()
        }
        super.onDestroy()
    }

    /**
     * ダウンロード通知を表示するために POST_NOTIFICATIONS パーミッションを要求する。
     * GeckoView の通知パーミッション要求が保留中の場合はスキップする。
     */
    private fun requestDownloadNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        // GeckoView の通知パーミッション要求が保留中の場合は競合を避けるためスキップ
        if (pendingNotificationPermissionResult != null) return
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    private fun openInMainBrowser(url: String) {
        val targetUri = Uri.parse(url)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = targetUri
            }
        )
        finish()
    }
}

@Composable
private fun CustomTabScreen(
    initialUrl: String,
    customTabsSessionToken: CustomTabsSessionToken?,
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
    onClose: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    onRequestDownloadNotificationPermission: () -> Unit,
) {
    val viewModel = viewModel(initializer = {
        CustomTabScreenViewModel(
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            webSuggestionRepository = webSuggestionRepository,
        )
    })
    val uiState by viewModel.uiState.collectAsState()
    val prewarmedSession = remember(customTabsSessionToken, initialUrl) {
        customTabsSessionToken?.let { token ->
            CustomTabsWarmupStore.consumePreparedSession(
                token = token,
                launchUrl = initialUrl,
            )
        }
    }
    val browserTab by produceState<BrowserTab?>(
        initialValue = null,
        key1 = browserTabController,
        key2 = initialUrl,
        key3 = prewarmedSession,
    ) {
        value = if (prewarmedSession != null) {
            browserTabController.createAndAppendTabWithSession(
                session = prewarmedSession,
                initialUrl = initialUrl,
            )
        } else {
            browserTabController.createAndAppendTab(initialUrl = initialUrl)
        }
    }
    DisposableEffect(browserTabController, browserTab?.tabId) {
        val tabId = browserTab?.tabId
        onDispose {
            if (tabId != null) {
                browserTabController.closeTab(tabId)
            }
        }
    }
    val activeTab = browserTab
    if (activeTab == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    GeckoBrowserTab(
        modifier = Modifier.fillMaxSize(),
        browserTab = activeTab,
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
        enableBackNavigation = false,
        customTabMode = true,
        onCloseCustomTab = onClose,
        onOpenInBrowser = onOpenInBrowser,
        onOpenNewSessionRequest = { uri ->
            activeTab.session.loadUri(uri)
            activeTab.session
        },
        onCloseTab = onClose,
        onHistoryRecord = uiState.callbacks::onHistoryRecord,
        onHistoryTitleUpdate = uiState.callbacks::onHistoryTitleUpdate,
        urlBarSuggestions = uiState.urlBarSuggestions,
        onUrlInputChanged = uiState.callbacks::onUrlInputChanged,
    )
}

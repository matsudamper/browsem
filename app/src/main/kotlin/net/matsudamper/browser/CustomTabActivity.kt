package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsSessionToken
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.feature.themecolor.ThemeColorWebExtension
import net.matsudamper.browser.screen.browser.CustomTabScreenViewModel
import net.matsudamper.browser.ui.common.BrowserTheme
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

class CustomTabActivity : ComponentActivity() {
    private val runtime: GeckoRuntime by inject()
    private val themeColorExtension: ThemeColorWebExtension by inject()
    private val mediaWebExtensionInstance: MediaWebExtension by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val tabRepository: TabRepository by inject()
    private val historyRepository: HistoryRepository by inject()
    private val webSuggestionRepository: WebSuggestionRepository by inject()

    private val browserViewModel: CustomTabBrowserViewModel by viewModels {
        viewModelFactory {
            initializer {
                CustomTabBrowserViewModel(
                    tabRepository = tabRepository,
                    runtime = runtime,
                    handoffToken = intent.getStringExtra(WindowOpenHandoffStore.EXTRA_HANDOFF_TOKEN),
                )
            }
        }
    }

    private var pendingDownloadNotificationPermissionDeferred: CompletableDeferred<Unit>? = null

    private val requestDownloadNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingDownloadNotificationPermissionDeferred?.complete(Unit)
        pendingDownloadNotificationPermissionDeferred = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // キーボード直上に未描画領域が生じても下層 Activity（WebApp 等）が透けないよう、
        // ウィンドウ背景を不透明にしておく。
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        runtime.settings.setExtensionsWebAPIEnabled(true)

        // 拡張機能は Koin の single で管理されるため、ここではセッション管理のみ担当する
        val browserTabController = browserViewModel.browserTabController
        val browserSessionLifecycleController = browserViewModel.browserSessionLifecycleController

        // window.open から引き渡されたセッションは URL では作り直せないため、Activity ではなく
        // ViewModel が持ち主になる。構成変更をまたいでもタブに載せた内容が失われない。
        val handedOffPopupSession = browserViewModel.handoffSession
        // プロセスごと終了したあと OS がタスクを作り直すと、ストアは空でセッションを取り出せない。
        // opener も道連れに失われているため復元しようがなく、ホームページに化けるくらいなら閉じる。
        if (intent.hasExtra(WindowOpenHandoffStore.EXTRA_HANDOFF_TOKEN) &&
            browserViewModel.handoffInitialUrl == null
        ) {
            finish()
            return
        }
        val initialUrl = browserViewModel.handoffInitialUrl
            ?: ExternalInitialUrlPolicy.sanitize(intent.dataString).orEmpty()
        val customTabsSessionToken = CustomTabsSessionToken.getSessionTokenFromIntent(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtime.settings.setEnterpriseRootsEnabled(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                WebAuthnCompatStartupGate(runtime = runtime) {
                    BrowserAppShell(
                        browserTabController = browserTabController,
                        browserSessionLifecycleController = browserSessionLifecycleController,
                        runtime = runtime,
                    ) { outerNavActions ->
                        CustomTabOpaqueShell {
                            CustomTabScreen(
                                initialUrl = initialUrl.takeIf { it.isNotBlank() }
                                    ?: browserSettings.resolvedHomepageUrl(),
                                handedOffPopupSession = handedOffPopupSession,
                                onHandedOffPopupSessionAttached = {
                                    browserViewModel.onHandoffSessionAttached()
                                    // 載せる前に window.close が呼ばれていた場合はここで閉じる
                                    if (browserViewModel.isHandoffCloseRequested) {
                                        finish()
                                    }
                                },
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
                                outerNavActions = outerNavActions,
                                onClose = ::finish,
                                onOpenInBrowser = ::openInMainBrowser,
                                onOpenNewTabInBrowser = ::openNewTabInMainBrowser,
                                onOpenPopupInCustomTab = { uri, openerTabId ->
                                    openWindowOpenRequestInCustomTab(
                                        uri = uri,
                                        openerTabId = openerTabId,
                                        browserTabController = browserTabController,
                                        browserSessionLifecycleController = browserSessionLifecycleController,
                                    )
                                },
                                onRequestDownloadNotificationPermission = {
                                    requestDownloadNotificationPermission()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        pendingDownloadNotificationPermissionDeferred?.cancel(
            CancellationException("Activity was destroyed before download notification permission completed."),
        )
        pendingDownloadNotificationPermissionDeferred = null
        super.onDestroy()
    }

    @VisibleForTesting
    internal fun browserTabControllerForTesting(): BrowserTabController {
        return browserViewModel.browserTabController
    }

    /**
     * ダウンロード通知を表示するために POST_NOTIFICATIONS パーミッションを要求し、
     * ユーザーが GRANT または DENY を選択するまで待機する。
     */
    private suspend fun requestDownloadNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // 別のダウンロード通知パーミッション要求が保留中の場合は合流して待機
        val existingDownloadDeferred = pendingDownloadNotificationPermissionDeferred
        if (existingDownloadDeferred != null) {
            existingDownloadDeferred.await()
            return
        }
        val deferred = CompletableDeferred<Unit>()
        pendingDownloadNotificationPermissionDeferred = deferred
        requestDownloadNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        deferred.await()
    }

    /**
     * カスタムタブの内容を通常ブラウザへ引き継いで開く。
     *
     * SessionState の flush 待ち（最大数百ミリ秒）と遷移を、composition に紐づくスコープでなく
     * Activity の lifecycleScope で完遂させる。タップ直後の再コンポーズで Composable が
     * composition から外れても処理がキャンセルされず、「ブラウザで開く」が無効化されないようにする。
     */
    private fun openInMainBrowser(url: String, tab: BrowserTab) {
        lifecycleScope.launch {
            startMainBrowser(url, captureFreshSessionState(tab))
        }
    }

    private fun openNewTabInMainBrowser(url: String, referrerUrl: String?) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                referrerUrl?.let { putExtra(EXTRA_NEW_TAB_REFERRER_URL, it) }
            },
        )
    }

    companion object {
        internal const val EXTRA_NEW_TAB_REFERRER_URL = "extra_new_tab_referrer_url"
    }

    private fun startMainBrowser(url: String, sessionState: String) {
        val targetUri = Uri.parse(url)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = targetUri
                // 履歴・スクロール位置などを引き継ぐため、SessionState をプロセス内ストアへ預けて
                // トークンのみを Intent に載せる（Intent extra へ直接載せると Binder サイズ上限に当たり得る）
                sessionState.takeIf { it.isNotBlank() }?.let { state ->
                    putExtra(
                        CustomTabHandoffStore.EXTRA_HANDOFF_TOKEN,
                        CustomTabHandoffStore.store(state),
                    )
                }
            },
        )
        finish()
    }
}

@Composable
private fun CustomTabScreen(
    initialUrl: String,
    handedOffPopupSession: GeckoSession?,
    onHandedOffPopupSessionAttached: () -> Unit,
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
    outerNavActions: OuterNavActions,
    onClose: () -> Unit,
    onOpenInBrowser: (url: String, tab: BrowserTab) -> Unit,
    onOpenNewTabInBrowser: (url: String, referrerUrl: String?) -> Unit,
    onOpenPopupInCustomTab: (uri: String, openerTabId: String) -> GeckoSession,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
) {
    val viewModel = viewModel(initializer = {
        CustomTabScreenViewModel(
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            webSuggestionRepository = webSuggestionRepository,
        )
    })
    val uiState by viewModel.uiState.collectAsState()
    val currentOnHandedOffPopupSessionAttached by rememberUpdatedState(onHandedOffPopupSessionAttached)
    val prewarmedSession = remember(customTabsSessionToken, initialUrl) {
        if (handedOffPopupSession != null) {
            null
        } else {
            customTabsSessionToken?.let { token ->
                CustomTabsWarmupStore.consumePreparedSession(
                    token = token,
                    launchUrl = initialUrl,
                )
            }
        }
    }
    val browserTab by produceState<BrowserTab?>(
        initialValue = null,
        key1 = browserTabController,
        key2 = initialUrl,
        key3 = prewarmedSession,
    ) {
        // BrowserAppShell の外側ナビ（サイトの設定など）で Root が一時的に外れるため、
        // 既存タブを再利用する。WebAppScreen と同様、破棄は ViewModel に任せる。
        value = browserTabController.tabs.firstOrNull()
            ?: when {
                handedOffPopupSession != null -> browserTabController.createTabWithHandedOffPopupSession(
                    session = handedOffPopupSession,
                    initialUrl = initialUrl,
                ).also { currentOnHandedOffPopupSessionAttached() }

                prewarmedSession != null -> browserTabController.createAndAppendTabWithSession(
                    session = prewarmedSession,
                    initialUrl = initialUrl,
                )

                else -> browserTabController.createAndAppendTab(initialUrl = initialUrl)
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

    val retainOpenersAfterDetach: (BrowserTab) -> Unit = {
        WindowOpenSessionPolicy.postAfterFrame {
            browserSessionLifecycleController.retainOpenersOfLivePopups(
                tabs = browserTabController.tabs,
                selectedTabId = browserTabController.selectedTabId,
            )
        }
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
        onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
        onOpenSettings = {},
        onOpenSiteSettings = { url ->
            outerNavActions.openSiteSettings(url, activeTab.tabId)
        },
        onOpenDownloads = null,
        onOpenTabs = {},
        enableTabUi = false,
        showInstallExtensionItem = false,
        customTabMode = true,
        onCloseCustomTab = onClose,
        onOpenInBrowser = { url -> onOpenInBrowser(url, activeTab) },
        onOpenNewSessionRequest = { uri ->
            onOpenPopupInCustomTab(uri, activeTab.tabId)
        },
        onOpenNewTabRequest = { uri, referrerUrl ->
            onOpenNewTabInBrowser(uri, referrerUrl)
        },
        onCloseTab = onClose,
        onHistoryRecord = uiState.callbacks::onHistoryRecord,
        onHistoryTitleUpdate = uiState.callbacks::onHistoryTitleUpdate,
        urlBarSuggestions = uiState.urlBarSuggestions,
        onUrlInputChanged = uiState.callbacks::onUrlInputChanged,
        onSessionDetachedFromView = retainOpenersAfterDetach,
    )
}

@Composable
internal fun CustomTabOpaqueShell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                testTagsAsResourceId = true
            },
    ) {
        content()
    }
}

@Preview(name = "CustomTabOpaqueShellLight", widthDp = 412, heightDp = 915)
@Preview(
    name = "CustomTabOpaqueShellDark",
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewCustomTabOpaqueShell() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        CustomTabOpaqueShell {
            CustomTabToolbar(
                title = "example.com",
                url = "https://example.com/page",
                onClose = {},
                toolbarColor = null,
                onRefresh = {},
                onSuperRefresh = {},
                onHome = {},
                onForward = {},
                canGoForward = false,
                onBack = {},
                canGoBack = true,
                onLongPressHistory = {},
                isPcMode = false,
                onPcModeToggle = {},
                showInstallExtensionItem = false,
                onInstallExtension = {},
                onTranslatePage = {},
                onShare = {},
                onFindInPage = {},
                onAddToHomeScreen = {},
                showAddToHomeScreen = true,
                onOpenInBrowser = {},
                onOpenSiteSettings = {},
                pageZoomPercent = 100,
                onPageZoomIn = {},
                onPageZoomOut = {},
                onResetPageZoom = {},
            )
        }
    }
}

/**
 * flushSessionState() で最新の SessionState を onSessionStateChange 経由で反映させ、
 * 更新後の [BrowserTab.sessionState] を返す。スクロール位置などを取りこぼさないために使う。
 * 反映が一定時間内に来ない場合（既に最新の場合を含む）は現在のキャッシュ値を返す。
 */
private suspend fun captureFreshSessionState(tab: BrowserTab): String {
    val before = tab.sessionState
    tab.session.flushSessionState()
    return withTimeoutOrNull(FLUSH_SESSION_STATE_TIMEOUT_MS) {
        snapshotFlow { tab.sessionState }.first { it != before }
    } ?: tab.sessionState
}

private const val FLUSH_SESSION_STATE_TIMEOUT_MS = 300L

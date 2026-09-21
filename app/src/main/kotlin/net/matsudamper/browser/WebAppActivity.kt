package net.matsudamper.browser

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.feature.themecolor.ThemeColorWebExtension
import net.matsudamper.browser.screen.browser.WebAppScreenViewModel
import net.matsudamper.browser.ui.browser.BrowserContentLoadingIndicator
import net.matsudamper.browser.ui.common.BrowserTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.mozilla.geckoview.GeckoRuntime

/**
 * ホームに「アプリとして追加」された場合に起動するActivity。
 * カスタムタブに近い外観だが、閉じるボタンはなく、バックボタンでブラウザ履歴を遡る。
 * 独立したタスクとして管理され、アプリの履歴（最近使ったアプリ）に残る。
 */
class WebAppActivity : ComponentActivity() {
    private val geckoRuntimeInitializer: GeckoRuntimeInitializer by inject()
    private var geckoRuntime: GeckoRuntime? by mutableStateOf(null)
    private val themeColorExtension: ThemeColorWebExtension by inject()
    private val mediaWebExtension: MediaWebExtension by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val historyRepository: HistoryRepository by inject()
    private val webSuggestionRepository: WebSuggestionRepository by inject()

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
        // GeckoRuntime の生成は設定値の読み出しを伴うため、メインスレッドをブロックせずに待つ。
        lifecycleScope.launch {
            val initialized = geckoRuntimeInitializer.initialize()
            if (isFinishing || isDestroyed) return@launch
            initialized.settings.setExtensionsWebAPIEnabled(true)
            geckoRuntime = initialized
        }

        val initialUrl = resolveInitialUrl()
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent
            val runtime = geckoRuntime ?: return@setContent

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtime.settings.setEnterpriseRootsEnabled(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                WebAuthnCompatStartupGate(runtime = runtime) {
                    val browserViewModel: WebAppBrowserViewModel = koinViewModel()
                    val browserTabController = browserViewModel.browserTabController
                    val browserSessionLifecycleController = browserViewModel.browserSessionLifecycleController
                    val reevaluateOpenerRetention: () -> Unit = {
                        WindowOpenSessionPolicy.postAfterFrame {
                            browserSessionLifecycleController.retainOpenersOfLivePopups(
                                tabs = browserTabController.tabs,
                                selectedTabId = browserTabController.selectedTabId,
                            )
                        }
                    }
                    val resolvedInitialUrl = initialUrl ?: browserSettings.resolvedHomepageUrl()
                    val webAppPinnedHost = runCatching { URI(resolvedInitialUrl).host }.getOrNull()
                    val webAppScreenViewModel: WebAppScreenViewModel = koinViewModel()
                    val uiState by webAppScreenViewModel.uiState.collectAsState()

                    BrowserAppShell(
                        browserTabController = browserTabController,
                        browserSessionLifecycleController = browserSessionLifecycleController,
                        runtime = runtime,
                    ) { outerNavActions ->
                        val browserTab by produceState<BrowserTab?>(
                            initialValue = null,
                            key1 = browserTabController,
                            key2 = resolvedInitialUrl,
                        ) {
                            // Activity再生成（フォルダブル開閉等）時はViewModelのcontrollerに既存タブが残っているため再利用する。
                            // タブの破棄はViewModelの onCleared() で行う。
                            value = browserTabController.tabs.firstOrNull()
                                ?: browserTabController.createAndAppendTab(initialUrl = resolvedInitialUrl)
                        }
                        val activeTab = browserTab
                        if (activeTab == null) {
                            BrowserContentLoadingIndicator()
                        } else {
                            val taskTitle = activeTab.title
                            val taskFavicon = activeTab.faviconBitmap
                            LaunchedEffect(taskTitle, taskFavicon) {
                                updateTaskDescription(taskTitle, taskFavicon)
                            }
                            val currentUrl = activeTab.currentUrl
                            LaunchedEffect(currentUrl) {
                                fetchHighQualityFavicon(activeTab, currentUrl)
                            }
                            GeckoBrowserTab(
                                modifier = Modifier.fillMaxSize(),
                                browserTab = activeTab,
                                homepageUrl = resolvedInitialUrl,
                                searchTemplate = browserSettings.resolvedSearchTemplate(),
                                translationProvider = browserSettings.translationProvider,
                                geminiNanoModelKey = browserSettings.geminiNanoModelKey,
                                themeColorExtension = themeColorExtension,
                                mediaWebExtension = mediaWebExtension,
                                browserSessionLifecycleController = browserSessionLifecycleController,
                                tabCount = 1,
                                onInstallExtensionRequest = {},
                                onRequestDownloadNotificationPermission = { requestDownloadNotificationPermission() },
                                onOpenSettings = {},
                                onOpenSiteSettings = { url ->
                                    outerNavActions.openSiteSettings(url, activeTab.tabId)
                                },
                                onOpenDownloads = null,
                                onOpenTabs = {},
                                enableTabUi = false,
                                showInstallExtensionItem = false,
                                customTabMode = false,
                                webAppMode = true,
                                webAppPinnedHost = webAppPinnedHost,
                                onWebAppCrossDomainNavigation = ::openInCustomTab,
                                onCloseCustomTab = null,
                                onOpenInBrowser = ::openInMainBrowser,
                                onOpenNewSessionRequest = { uri ->
                                    openWindowOpenRequestInCustomTab(
                                        uri = uri,
                                        openerTabId = activeTab.tabId,
                                        browserTabController = browserTabController,
                                        browserSessionLifecycleController = browserSessionLifecycleController,
                                    )
                                },
                                onOpenNewTabRequest = { uri, referrerUrl ->
                                    openNewTabInMainBrowser(uri, referrerUrl)
                                },
                                onCloseTab = null,
                                externalDownloadDialogListener = null,
                                externalTabInitialUrl = null,
                                onToolbarHorizontalDrag = {},
                                onToolbarDragEnd = {},
                                onHistoryRecord = uiState.callbacks::onHistoryRecord,
                                onHistoryTitleUpdate = uiState.callbacks::onHistoryTitleUpdate,
                                urlBarSuggestions = uiState.urlBarSuggestions,
                                onUrlInputChanged = uiState.callbacks::onUrlInputChanged,
                                onReevaluateOpenerRetention = reevaluateOpenerRetention,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openNewTabInMainBrowser(url: String, referrerUrl: String?) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                referrerUrl?.let { putExtra(CustomTabActivity.EXTRA_NEW_TAB_REFERRER_URL, it) }
            },
        )
    }

    override fun onDestroy() {
        pendingDownloadNotificationPermissionDeferred?.cancel(
            CancellationException("Activity was destroyed before download notification permission completed."),
        )
        pendingDownloadNotificationPermissionDeferred = null
        super.onDestroy()
    }

    private fun openInCustomTab(url: String) {
        CustomTabsIntent.Builder()
            .build()
            .apply { intent.setPackage(packageName) }
            .launchUrl(this, Uri.parse(url))
    }

    /**
     * 現在のURLを通常ブラウザで開く。
     * ウェブアプリ側は閉じずにそのまま維持する。
     */
    private fun openInMainBrowser(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
            },
        )
    }

    /**
     * Recents に表示するタイトルとアイコンを更新する。
     * favicon が無い場合はアイコン未指定とし、ランチャー側のデフォルトに任せる。
     *
     * 公開 API の TaskDescription.Builder.setIcon は drawable resource id のみ受け付け、
     * Bitmap を渡すには deprecated コンストラクタを使う必要があるため、ここでは
     * Bitmap を直接渡す旧コンストラクタを使用する。
     */
    @Suppress("DEPRECATION")
    private fun updateTaskDescription(title: String, favicon: Bitmap?) {
        val label = title.takeIf { it.isNotBlank() }
        val description = ActivityManager.TaskDescription(label, favicon)
        setTaskDescription(description)
    }

    /**
     * HomeScreenIconFetcher を用いてページの高品質アイコンを取得し、BrowserTab に保存する。
     * GeckoView が提供する 16x16 favicon や、<origin>/favicon.ico が 404 を返すサイトでも
     * apple-touch-icon や Web App Manifest 由来の大きなアイコンを取得できる。
     *
     * ただしセッションが既に favicon を持っている場合は上書きしない。
     * HomeScreenIconFetcher は Gecko セッション外の HttpURLConnection で取得するため、
     * 認証ページが未認証リクエストをログイン/ランディングページへリダイレクトすると
     * 無関係なアイコンを掴む恐れがある。あくまで favicon が無い場合のフォールバックに留める。
     */
    private suspend fun fetchHighQualityFavicon(browserTab: BrowserTab, pageUrl: String) {
        if (pageUrl.isBlank()) return
        if (browserTab.faviconBitmap != null) return
        val fetched = try {
            HomeScreenIconFetcher.fetchIcon(pageUrl = pageUrl, webAppManifestJson = null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        // fetch 中にセッション側が favicon を設定した場合も上書きしない
        if (browserTab.currentUrl == pageUrl && browserTab.faviconBitmap == null) {
            browserTab.faviconBitmap = fetched
        }
    }

    /**
     * Intentのデータから安全なURLを取り出す。
     * ACTION_VIEW かつ http/https スキームの場合のみURLとして採用し、
     * それ以外は null を返してホームページにフォールバックさせる。
     */
    private fun resolveInitialUrl(): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        return ExternalInitialUrlPolicy.sanitize(intent.dataString)
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
}

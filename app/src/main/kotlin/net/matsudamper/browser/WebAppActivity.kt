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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.feature.themecolor.ThemeColorWebExtension
import net.matsudamper.browser.screen.browser.WebAppScreenViewModel
import net.matsudamper.browser.ui.browser.WebAppScreen
import net.matsudamper.browser.ui.common.BrowserTheme
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.GeckoRuntime

/**
 * ホームに「アプリとして追加」された場合に起動するActivity。
 * カスタムタブに近い外観だが、閉じるボタンはなく、バックボタンでブラウザ履歴を遡る。
 * 独立したタスクとして管理され、アプリの履歴（最近使ったアプリ）に残る。
 */
class WebAppActivity : ComponentActivity() {
    private val runtime: GeckoRuntime by inject()
    private val themeColorExtension: ThemeColorWebExtension by inject()
    private val mediaWebExtension: MediaWebExtension by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val tabRepository: TabRepository by inject()
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
        runtime.settings.setExtensionsWebAPIEnabled(true)

        // 外部アプリから任意のURLが渡されないよう、http/https スキームのみ許可する
        val initialUrl = resolveInitialUrl()
        setContent {
            val browserViewModel = viewModel(initializer = {
                WebAppBrowserViewModel(
                    tabRepository = tabRepository,
                    runtime = runtime,
                )
            })
            val browserTabController = browserViewModel.browserTabController
            val browserSessionLifecycleController = browserViewModel.browserSessionLifecycleController
            val popupController = browserViewModel.popupController
            val retainOpenersAfterDetach: (BrowserTab) -> Unit = {
                WindowOpenSessionPolicy.postAfterFrame {
                    browserSessionLifecycleController.retainOpenersOfLivePopups(
                        tabs = browserTabController.tabs,
                        selectedTabId = browserTabController.selectedTabId,
                    )
                }
            }
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent
            val resolvedInitialUrl = initialUrl ?: browserSettings.resolvedHomepageUrl()
            val webAppPinnedHost = runCatching { java.net.URI(resolvedInitialUrl).host }.getOrNull()
            val webAppScreenViewModel = viewModel(initializer = {
                WebAppScreenViewModel(
                    historyRepository = historyRepository,
                    settingsRepository = settingsRepository,
                    webSuggestionRepository = webSuggestionRepository,
                )
            })
            val uiState by webAppScreenViewModel.uiState.collectAsState()

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtime.settings.setEnterpriseRootsEnabled(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                BrowserAppShell(
                    browserTabController = browserTabController,
                    browserSessionLifecycleController = browserSessionLifecycleController,
                    runtime = runtime,
                ) { outerNavActions ->
                    WebAppScreen(
                        initialUrl = resolvedInitialUrl,
                        browserTabController = browserTabController,
                        uiState = uiState,
                    ) { modifier, browserTab, webAppUiState ->
                        val taskTitle = browserTab.title
                        val taskFavicon = browserTab.faviconBitmap
                        LaunchedEffect(taskTitle, taskFavicon) {
                            updateTaskDescription(taskTitle, taskFavicon)
                        }
                        val currentUrl = browserTab.currentUrl
                        LaunchedEffect(currentUrl) {
                            fetchHighQualityFavicon(browserTab, currentUrl)
                        }
                        GeckoBrowserTab(
                            modifier = modifier,
                            browserTab = browserTab,
                            homepageUrl = resolvedInitialUrl,
                            searchTemplate = browserSettings.resolvedSearchTemplate(),
                            translationProvider = browserSettings.translationProvider,
                            themeColorExtension = themeColorExtension,
                            mediaWebExtension = mediaWebExtension,
                            browserSessionLifecycleController = browserSessionLifecycleController,
                            tabCount = 1,
                            onInstallExtensionRequest = {},
                            onRequestDownloadNotificationPermission = { requestDownloadNotificationPermission() },
                            onOpenSettings = {},
                            onOpenSiteSettings = { url ->
                                outerNavActions.openSiteSettings(url, browserTab.tabId)
                            },
                            onOpenDownloads = null,
                            onOpenTabs = {},
                            enableTabUi = false,
                            showInstallExtensionItem = false,
                            webAppMode = true,
                            webAppPinnedHost = webAppPinnedHost,
                            onWebAppCrossDomainNavigation = ::openInCustomTab,
                            onOpenInBrowser = ::openInMainBrowser,
                            onOpenNewSessionRequest = { uri ->
                                popupController.open(uri, browserTab.tabId)
                            },
                            onOpenNewTabRequest = { uri, referrerUrl ->
                                openNewTabInMainBrowser(uri, referrerUrl)
                            },
                            onHistoryRecord = webAppUiState.callbacks::onHistoryRecord,
                            onHistoryTitleUpdate = webAppUiState.callbacks::onHistoryTitleUpdate,
                            urlBarSuggestions = webAppUiState.urlBarSuggestions,
                            onUrlInputChanged = webAppUiState.callbacks::onUrlInputChanged,
                            onSessionDetachedFromView = retainOpenersAfterDetach,
                        )
                        popupController.top?.let { popupTab ->
                            WindowOpenOverlayDialog(onDismissRequest = popupController::dismissTop) {
                                GeckoBrowserTab(
                                    modifier = Modifier.fillMaxSize(),
                                    browserTab = popupTab,
                                    homepageUrl = resolvedInitialUrl,
                                    searchTemplate = browserSettings.resolvedSearchTemplate(),
                                    translationProvider = browserSettings.translationProvider,
                                    themeColorExtension = themeColorExtension,
                                    mediaWebExtension = mediaWebExtension,
                                    browserSessionLifecycleController = browserSessionLifecycleController,
                                    tabCount = 1,
                                    onInstallExtensionRequest = {},
                                    onRequestDownloadNotificationPermission = {
                                        requestDownloadNotificationPermission()
                                    },
                                    onOpenSettings = {},
                                    onOpenSiteSettings = { url ->
                                        outerNavActions.openSiteSettings(url, popupTab.tabId)
                                    },
                                    onOpenDownloads = null,
                                    onOpenTabs = {},
                                    enableTabUi = false,
                                    showInstallExtensionItem = false,
                                    customTabMode = true,
                                    onCloseCustomTab = popupController::dismissTop,
                                    onCloseTab = popupController::dismissTop,
                                    onOpenInBrowser = ::openInMainBrowser,
                                    onOpenNewSessionRequest = { uri ->
                                        popupController.open(uri, popupTab.tabId)
                                    },
                                    onOpenNewTabRequest = { uri, referrerUrl ->
                                        openNewTabInMainBrowser(uri, referrerUrl)
                                    },
                                    onHistoryRecord = webAppUiState.callbacks::onHistoryRecord,
                                    onHistoryTitleUpdate = webAppUiState.callbacks::onHistoryTitleUpdate,
                                    urlBarSuggestions = webAppUiState.urlBarSuggestions,
                                    onUrlInputChanged = webAppUiState.callbacks::onUrlInputChanged,
                                    onSessionDetachedFromView = retainOpenersAfterDetach,
                                )
                            }
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
                data = android.net.Uri.parse(url)
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

    /**
     * 別ドメインの URL を Custom Tabs で開く。
     */
    private fun openInCustomTab(url: String) {
        startActivity(
            Intent(this, CustomTabActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
            },
        )
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
        val data = intent.data ?: return null
        val scheme = data.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        return data.toString()
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

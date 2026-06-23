package net.matsudamper.browser

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.screen.browser.WebAppScreenViewModel
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.browser.WebAppScreen
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.GeckoRuntime

import java.util.concurrent.CancellationException

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
        ActivityResultContracts.RequestPermission()
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
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent
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
                WebAppScreen(
                    initialUrl = initialUrl ?: browserSettings.resolvedHomepageUrl(),
                    browserTabController = browserTabController,
                    uiState = uiState,
                ) { modifier, browserTab, webAppUiState ->
                    // Recents (最近のアプリ) のサムネイル左上に表示されるアイコンとラベルを
                    // ページのタイトル / favicon に追従させる。setTaskDescription が呼ばれない
                    // 場合はアプリの label / icon (ic_firefox_like) にフォールバックするため、
                    // ホーム追加のアプリピンと同じく "アプリのデフォルトアイコン" に見えてしまう。
                    val taskTitle = browserTab.title
                    val taskFavicon = browserTab.faviconBitmap
                    LaunchedEffect(taskTitle, taskFavicon) {
                        updateTaskDescription(taskTitle, taskFavicon)
                    }
                    // GeckoView が onMetadataChanged で配る favicon は小さい (16x16 程度) ことが
                    // 多く、また <origin>/favicon.ico を返さないサイト (例: sns.plusmember.jp) では
                    // BrowserTabScreenState の fetchFavicon が失敗し faviconBitmap が null になる。
                    // ホーム追加のアプリピンから WebAppActivity を起動すると、その時点では favicon
                    // の手がかりが無く Recents アイコンがデフォルトアイコンのままになるため、
                    // ここで HomeScreenIconFetcher を走らせて apple-touch-icon / manifest 由来の
                    // 高品質アイコンを取得しておく。
                    val currentUrl = browserTab.currentUrl
                    LaunchedEffect(currentUrl) {
                        fetchHighQualityFavicon(browserTab, currentUrl)
                    }
                    GeckoBrowserTab(
                        modifier = modifier,
                        browserTab = browserTab,
                        homepageUrl = browserSettings.resolvedHomepageUrl(),
                        searchTemplate = browserSettings.resolvedSearchTemplate(),
                        translationProvider = browserSettings.translationProvider,
                        themeColorExtension = themeColorExtension,
                        mediaWebExtension = mediaWebExtension,
                        browserSessionLifecycleController = browserSessionLifecycleController,
                        tabCount = 1,
                        onInstallExtensionRequest = {},
                        onRequestDownloadNotificationPermission = { requestDownloadNotificationPermission() },
                        onOpenSettings = {},
                        // ウェブアプリモードは設定画面のナビゲーションスタックを持たないため非表示
                        onOpenSiteSettings = null,
                        onOpenDownloads = null,
                        onOpenTabs = {},
                        enableTabUi = false,
                        showInstallExtensionItem = false,
                        // ウェブアプリモード: 閉じるボタンなし、カスタムタブ風のツールバー
                        webAppMode = true,
                        // onLoadRequest で TARGET_WINDOW_NEW を現在タブへ畳み込むため、
                        // ここへ到達することは想定しない。GeckoView 契約上 null を返して安全に拒否する。
                        onOpenNewSessionRequest = { null },
                        onOpenNewTabRequest = { uri, referrerUrl ->
                            openNewTabInMainBrowser(uri, referrerUrl)
                        },
                        onHistoryRecord = webAppUiState.callbacks::onHistoryRecord,
                        onHistoryTitleUpdate = webAppUiState.callbacks::onHistoryTitleUpdate,
                        urlBarSuggestions = webAppUiState.urlBarSuggestions,
                        onUrlInputChanged = webAppUiState.callbacks::onUrlInputChanged,
                    )
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
            }
        )
    }

    override fun onDestroy() {
        pendingDownloadNotificationPermissionDeferred?.cancel(
            CancellationException("Activity was destroyed before download notification permission completed.")
        )
        pendingDownloadNotificationPermissionDeferred = null
        super.onDestroy()
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
        ) return
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

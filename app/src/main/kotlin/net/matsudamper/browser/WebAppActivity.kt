package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    private lateinit var browserTabController: BrowserTabController
    private lateinit var browserSessionLifecycleController: BrowserSessionLifecycleController

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

        // 拡張機能は Koin の single で管理されるため、ここではセッション管理のみ担当する
        browserTabController = BrowserTabController(
            tabRepository = tabRepository,
            tabGroupRepository = null,
            isSinglePage = true,
        )
        browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

        // 外部アプリから任意のURLが渡されないよう、http/https スキームのみ許可する
        val initialUrl = resolveInitialUrl()
        setContent {
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
                        onOpenTabs = {},
                        enableTabUi = false,
                        showInstallExtensionItem = false,
                        // ウェブアプリモード: 閉じるボタンなし、カスタムタブ風のツールバー
                        webAppMode = true,
                        onOpenNewSessionRequest = { uri ->
                            // ウェブアプリモードでは新規タブを作成せず、同じセッションでURLを読み込む
                            // 新規タブを作成するとユーザーから見えないタブになるため
                            browserTab.session.loadUri(uri)
                            browserTab.session
                        },
                        onOpenNewTabRequest = { uri ->
                            browserTab.session.loadUri(uri)
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

    override fun onDestroy() {
        pendingDownloadNotificationPermissionDeferred?.cancel(
            CancellationException("Activity was destroyed before download notification permission completed.")
        )
        pendingDownloadNotificationPermissionDeferred = null
        // セッションのみ閉じる。拡張機能はプロセススコープで管理されるため解放しない。
        if (::browserTabController.isInitialized) {
            browserTabController.close()
        }
        super.onDestroy()
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

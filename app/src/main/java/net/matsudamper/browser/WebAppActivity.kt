package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.screen.webapp.WebAppScreen
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

        // 外部アプリから任意のURLが渡されないよう、http/https スキームのみ許可する
        val initialUrl = resolveInitialUrl()
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtimeCoordinator.applyRuntimeSettings(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                WebAppScreen(
                    initialUrl = initialUrl ?: browserSettings.resolvedHomepageUrl(),
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


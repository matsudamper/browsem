package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsSessionToken
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryEntry
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.media.MediaWebExtension
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CancellationException

class CustomTabActivity : ComponentActivity() {
    private val runtime: GeckoRuntime by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val historyRepository: HistoryRepository by inject()

    private lateinit var browserSessionController: BrowserSessionController
    private lateinit var themeColorExtension: ThemeColorWebExtension
    private lateinit var mediaWebExtension: MediaWebExtension

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

        browserSessionController = BrowserSessionController(runtime)
        themeColorExtension = ThemeColorWebExtension().also { it.install(runtime) }
        mediaWebExtension = MediaWebExtension(applicationContext).also { it.install(runtime) }

        val initialUrl = intent.dataString.orEmpty()
        val customTabsSessionToken = CustomTabsSessionToken.getSessionTokenFromIntent(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val browserSettings = settings ?: return@setContent

            LaunchedEffect(browserSettings.enableThirdPartyCa) {
                runtime.settings.setEnterpriseRootsEnabled(browserSettings.enableThirdPartyCa)
            }

            BrowserTheme(themeMode = browserSettings.themeMode) {
                CustomTabScreen(
                    initialUrl = initialUrl.takeIf { it.isNotBlank() } ?: browserSettings.resolvedHomepageUrl(),
                    customTabsSessionToken = customTabsSessionToken,
                    homepageUrl = browserSettings.resolvedHomepageUrl(),
                    searchTemplate = browserSettings.resolvedSearchTemplate(),
                    translationProvider = browserSettings.translationProvider,
                    browserSessionController = browserSessionController,
                    historyRepository = historyRepository,
                    themeColorExtension = themeColorExtension,
                    mediaWebExtension = mediaWebExtension,
                    onClose = ::finish,
                    onOpenInBrowser = ::openInMainBrowser,
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
        if (::browserSessionController.isInitialized) {
            browserSessionController.close()
        }
        if (::themeColorExtension.isInitialized) {
            themeColorExtension.cleanup()
        }
        if (::mediaWebExtension.isInitialized) {
            mediaWebExtension.cleanup()
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
    browserSessionController: BrowserSessionController,
    historyRepository: HistoryRepository,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    onClose: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
) {
    val prewarmedSession = remember(customTabsSessionToken, initialUrl) {
        customTabsSessionToken?.let { token ->
            CustomTabsWarmupStore.consumePreparedSession(
                token = token,
                launchUrl = initialUrl,
            )
        }
    }
    val browserTab = remember(browserSessionController, initialUrl, prewarmedSession) {
        if (prewarmedSession != null) {
            browserSessionController.createAndAppendTabWithSession(
                session = prewarmedSession,
                initialUrl = initialUrl,
            )
        } else {
            browserSessionController.createAndAppendTab(initialUrl = initialUrl)
        }
    }

    var historySuggestions by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var historySuggestionQuery by remember { mutableStateOf("") }

    LaunchedEffect(historySuggestionQuery, historyRepository) {
        val flow = if (historySuggestionQuery.isBlank()) {
            historyRepository.getRecentSuggestions(limit = 8)
        } else {
            historyRepository.searchSuggestions(query = historySuggestionQuery, limit = 8)
        }
        flow.collectLatest { entries ->
            historySuggestions = entries
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
        enableBackNavigation = false,
        customTabMode = true,
        onCloseCustomTab = onClose,
        onOpenInBrowser = onOpenInBrowser,
        onOpenNewSessionRequest = { uri ->
            browserTab.session.loadUri(uri)
            browserTab.session
        },
        onCloseTab = onClose,
        onHistoryRecord = { url, title -> historyRepository.recordVisit(url, title) },
        onHistoryTitleUpdate = { id, title -> historyRepository.updateTitle(id, title) },
        historySuggestions = historySuggestions,
        onUrlInputChanged = { query ->
            historySuggestionQuery = query
        },
    )
}

package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsSessionToken
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity() {

    private val runtime: GeckoRuntime by inject()
    private val browserViewModel: BrowserViewModel by viewModel()
    private lateinit var extensionInstaller: WebExtensionInstaller
    private var pendingActivityResult: GeckoResult<Intent>? = null
    private var webExtensionWarmUpCompleted = false
    private var webExtensionWarmUpInProgress = false
    private var webExtensionWarmUpRetryCount = 0
    private var lastProcessedDeepLinkUrl: String? = null
    private val createNewTabChannel = Channel<String>(Channel.UNLIMITED)
    private val openDownloadsChannel = Channel<Unit>(Channel.CONFLATED)

    // リコンポーズのたびに新しい Flow が生成されチャネルがキャンセルされるのを防ぐため、
    // Composable の外でプロパティとして保持する
    private val newTabUrlFlow = createNewTabChannel.receiveAsFlow()
    private val openDownloadsFlow = openDownloadsChannel.receiveAsFlow()

    private var pendingDownloadNotificationPermissionDeferred: CompletableDeferred<Unit>? = null
    private var hostsBrowserContent = false
    private var systemNavigationObserverCallback: Any? = null

    private val requestDownloadNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingDownloadNotificationPermissionDeferred?.complete(Unit)
        pendingDownloadNotificationPermissionDeferred = null
    }

    private val geckoActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pendingResult = pendingActivityResult ?: return@registerForActivityResult
        pendingActivityResult = null

        if (result.resultCode == RESULT_OK) {
            pendingResult.complete(result.data ?: Intent())
        } else {
            pendingResult.completeExceptionally(
                CancellationException("Gecko activity cancelled. resultCode=${result.resultCode}")
            )
        }
    }

    private val activityDelegate = GeckoRuntime.ActivityDelegate { pendingIntent ->
        if (pendingActivityResult != null) {
            return@ActivityDelegate GeckoResult.fromException(
                IllegalStateException("Another Gecko activity request is already pending.")
            )
        }

        val result = GeckoResult<Intent>()
        pendingActivityResult = result

        try {
            geckoActivityLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        } catch (e: IntentSender.SendIntentException) {
            pendingActivityResult = null
            result.completeExceptionally(e)
        }

        result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lastProcessedDeepLinkUrl = savedInstanceState?.getString(KEY_PROCESSED_DEEPLINK_URL)
        if (intent.isCustomTabLaunchIntent()) {
            launchCustomTabActivity(intent)
            finish()
            return
        }
        hostsBrowserContent = true
        registerSystemNavigationObserverIfAvailable()
        extensionInstaller = WebExtensionInstaller(runtime)

        runtime.setActivityDelegate(activityDelegate)
        runtime.settings.setExtensionsWebAPIEnabled(true)
        runtime.webExtensionController.setPromptDelegate(extensionInstaller.promptDelegate)
        runtime.webExtensionController.setAddonManagerDelegate(extensionInstaller.addonManagerDelegate)
        warmUpWebExtensionController()

        // ACTION_OPEN_DOWNLOADS は savedInstanceState の有無にかかわらず処理する。
        // savedInstanceState != null (OS によるプロセスキル後の復元) の場合でも
        // ダウンロード通知タップでダウンロード画面へ遷移させるため。
        if (intent.action == DownloadWorker.ACTION_OPEN_DOWNLOADS) {
            openDownloadsChannel.trySend(Unit)
        } else {
            val url = intent.dataString
            // 設定変更（画面回転等）後の再起動では直前に処理した URL を復元し、
            // 同じ URL であれば重複タブを作らないようスキップする。
            if (url != null && url != lastProcessedDeepLinkUrl) {
                val result = createNewTabChannel.trySend(url)
                if (result.isFailure) {
                    Log.e("MainActivity", "URL の送信に失敗: $url, reason=${result.exceptionOrNull()}")
                } else {
                    lastProcessedDeepLinkUrl = url
                }
            }
        }

        setContent {
            Box(
                modifier = Modifier.semantics {
                    testTagsAsResourceId = true
                },
            ) {
                BrowserApp(
                    viewModel = browserViewModel,
                    newTabUrlFlow = newTabUrlFlow,
                    openDownloadsFlow = openDownloadsFlow,
                    onInstallExtensionRequest = { pageUrl ->
                        extensionInstaller.installFromCurrentPage(pageUrl)
                    },
                    onRequestDownloadNotificationPermission = {
                        requestDownloadNotificationPermission()
                    },
                )
            }
            extensionInstaller.installPromptState?.let { prompt ->
                InstallPromptDialog(
                    prompt = prompt,
                    resolveInstallPrompt = extensionInstaller::resolveInstallPrompt,
                )
            }
            extensionInstaller.installFailureMessage?.let { message ->
                InstallFailureDialog(
                    message = message,
                    onDismiss = extensionInstaller::dismissInstallFailure,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.isCustomTabLaunchIntent()) {
            launchCustomTabActivity(intent, finishCurrentTask = true)
            return
        }
        if (intent.action == DownloadWorker.ACTION_OPEN_DOWNLOADS) {
            openDownloadsChannel.trySend(Unit)
            return
        }
        val url = intent.dataString
        if (url != null) {
            val result = createNewTabChannel.trySend(url)
            if (result.isFailure) {
                Log.e("MainActivity", "URL の送信に失敗: $url, reason=${result.exceptionOrNull()}")
            } else {
                lastProcessedDeepLinkUrl = url
            }
        }
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
        pendingDownloadNotificationPermissionDeferred?.let {
            it.await()
            return
        }
        val deferred = CompletableDeferred<Unit>()
        pendingDownloadNotificationPermissionDeferred = deferred
        requestDownloadNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        deferred.await()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 処理済み deeplink URL を保存して設定変更後の重複タブ作成を防ぐ
        lastProcessedDeepLinkUrl?.let { outState.putString(KEY_PROCESSED_DEEPLINK_URL, it) }
    }

    override fun onResume() {
        super.onResume()
        if (::extensionInstaller.isInitialized) {
            warmUpWebExtensionController()
        }
    }

    override fun onDestroy() {
        unregisterSystemNavigationObserverIfNeeded()
        if (hostsBrowserContent && isFinishing) {
            runCatching {
                runBlocking {
                    browserViewModel.cleanupSelectedExternalTabOnActivityFinishIfNeeded()
                }
            }.onFailure { error ->
                Log.e("MainActivity", "外部タブの終了クリーンアップに失敗", error)
            }
        }
        if (::extensionInstaller.isInitialized) {
            extensionInstaller.cleanup()
        }
        pendingActivityResult?.completeExceptionally(
            CancellationException("Activity was destroyed before Gecko activity completed.")
        )
        pendingActivityResult = null
        pendingDownloadNotificationPermissionDeferred?.cancel(
            CancellationException("Activity was destroyed before download notification permission completed.")
        )
        pendingDownloadNotificationPermissionDeferred = null
        if (::extensionInstaller.isInitialized && runtime.getActivityDelegate() === activityDelegate) {
            runtime.setActivityDelegate(null)
        }
        if (::extensionInstaller.isInitialized &&
            runtime.webExtensionController.getPromptDelegate() === extensionInstaller.promptDelegate
        ) {
            runtime.webExtensionController.setPromptDelegate(null)
        }
        runtime.webExtensionController.setAddonManagerDelegate(null)
        super.onDestroy()
    }

    private fun warmUpWebExtensionController() {
        if (webExtensionWarmUpCompleted || webExtensionWarmUpInProgress) {
            return
        }
        webExtensionWarmUpInProgress = true
        runtime.webExtensionController.list().accept(
            {
                webExtensionWarmUpInProgress = false
                webExtensionWarmUpCompleted = true
            },
            {
                webExtensionWarmUpInProgress = false
                webExtensionWarmUpRetryCount++
                if (!isFinishing && !isDestroyed && webExtensionWarmUpRetryCount < MAX_WARMUP_RETRIES) {
                    window.decorView.postDelayed(
                        { warmUpWebExtensionController() },
                        1200L
                    )
                } else if (webExtensionWarmUpRetryCount >= MAX_WARMUP_RETRIES) {
                    Log.w("MainActivity", "WebExtension warmup を ${MAX_WARMUP_RETRIES}回リトライしても失敗")
                }
            },
        )
    }

    companion object {
        private const val MAX_WARMUP_RETRIES = 5
        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_SESSION_ID = "androidx.browser.customtabs.extra.SESSION_ID"
        private const val KEY_PROCESSED_DEEPLINK_URL = "processed_deeplink_url"
    }

    private fun Intent.isCustomTabLaunchIntent(): Boolean {
        if (action != Intent.ACTION_VIEW) return false
        if (CustomTabsSessionToken.getSessionTokenFromIntent(this) != null) {
            return true
        }
        val extras = extras ?: return false
        return extras.containsKey(EXTRA_CUSTOM_TABS_SESSION) ||
            extras.containsKey(EXTRA_CUSTOM_TABS_SESSION_ID)
    }

    private fun launchCustomTabActivity(
        sourceIntent: Intent,
        finishCurrentTask: Boolean = false,
    ) {
        val customTabIntent = Intent(this, CustomTabActivity::class.java).apply {
            action = sourceIntent.action
            data = sourceIntent.data
            sourceIntent.extras?.let { putExtras(it) }
        }
        startActivity(customTabIntent)
        if (finishCurrentTask) {
            finishAndRemoveTask()
        }
    }

    private fun registerSystemNavigationObserverIfAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return
        }
        registerSystemNavigationObserverApi36()
    }

    private fun unregisterSystemNavigationObserverIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return
        }
        unregisterSystemNavigationObserverApi36()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun registerSystemNavigationObserverApi36() {
        if (systemNavigationObserverCallback != null) {
            return
        }
        val callback = OnBackInvokedCallback {
            lifecycleScope.launch {
                runCatching {
                    browserViewModel.cleanupSelectedExternalTabOnActivityFinishIfNeeded()
                }.onFailure { error ->
                    Log.e("MainActivity", "外部タブのバッククリーンアップに失敗", error)
                }
            }
        }
        runCatching {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER,
                callback,
            )
            systemNavigationObserverCallback = callback
        }.onFailure { error ->
            Log.w(
                "MainActivity",
                "システムナビゲーション observer の登録に失敗。終了時クリーンアップへフォールバック",
                error,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun unregisterSystemNavigationObserverApi36() {
        val callback = systemNavigationObserverCallback as? OnBackInvokedCallback ?: return
        runCatching {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
        systemNavigationObserverCallback = null
    }
}

@Composable
private fun InstallPromptDialog(
    prompt: InstallPromptState,
    resolveInstallPrompt: (allow: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { resolveInstallPrompt(false) },
        title = { Text("Install extension") },
        text = { Text(prompt.message) },
        confirmButton = {
            TextButton(onClick = { resolveInstallPrompt(true) }) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(onClick = { resolveInstallPrompt(false) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InstallFailureDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extension install failed") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

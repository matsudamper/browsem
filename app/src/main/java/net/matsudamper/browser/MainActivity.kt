package net.matsudamper.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.net.URI
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity() {

    private lateinit var runtime: GeckoRuntime
    private lateinit var extensionInstaller: WebExtensionInstaller
    private var pendingActivityResult: GeckoResult<Intent>? = null
    private var webExtensionWarmUpCompleted = false
    private var webExtensionWarmUpInProgress = false
    private val createNewTabChannel = Channel<String>(Channel.UNLIMITED)
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

    private val webNotificationDelegate = object : WebNotificationDelegate {
        override fun onShowNotification(notification: WebNotification) {
            val source = notification.source ?: return
            val domain = extractDomain(source)
            val channelId = ensureNotificationChannel(domain)

            val notificationId =
                (notification.tag.takeIf { it.isNotBlank() } ?: source).hashCode()
            val builder = NotificationCompat.Builder(this@MainActivity, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(notification.title)
                .setContentText(notification.text)
                .setAutoCancel(true)

            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this@MainActivity)
                    .notify(notificationId, builder.build())
            }
        }

        override fun onCloseNotification(notification: WebNotification) {
            val source = notification.source ?: return
            val notificationId =
                (notification.tag.takeIf { it.isNotBlank() } ?: source).hashCode()
            NotificationManagerCompat.from(this@MainActivity).cancel(notificationId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = GeckoRuntime.getDefault(this)
        extensionInstaller = WebExtensionInstaller(runtime)

        runtime.setActivityDelegate(activityDelegate)
        runtime.settings.setExtensionsWebAPIEnabled(true)
        runtime.webExtensionController.setPromptDelegate(extensionInstaller.promptDelegate)
        runtime.webExtensionController.setAddonManagerDelegate(extensionInstaller.addonManagerDelegate)
        runtime.webNotificationDelegate = webNotificationDelegate
        ThemeColorWebExtension.install(runtime)
        warmUpWebExtensionController()

        if (savedInstanceState == null) {
            val url = intent.dataString
            if (url != null) {
                createNewTabChannel.trySend(url)
            }
        }

        setContent {
            val browserViewModel = viewModel<BrowserViewModel>(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return BrowserViewModel(runtime, applicationContext) as T
                    }
                }
            )
            Box(
                modifier = Modifier.semantics {
                    testTagsAsResourceId = true
                },
            ) {
                BrowserApp(
                    viewModel = browserViewModel,
                    newTabUrlFlow = createNewTabChannel.receiveAsFlow(),
                    onInstallExtensionRequest = { pageUrl ->
                        extensionInstaller.installFromCurrentPage(pageUrl)
                    },
                    onDesktopNotificationPermissionRequest = {
                        requestNotificationPermissionIfNeeded()
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
        setIntent(intent)
        val url = intent.dataString
        if (url != null) {
            createNewTabChannel.trySend(url)
        }
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

    override fun onResume() {
        super.onResume()
        warmUpWebExtensionController()
    }

    override fun onDestroy() {
        extensionInstaller.cleanup()
        pendingActivityResult?.completeExceptionally(
            CancellationException("Activity was destroyed before Gecko activity completed.")
        )
        pendingActivityResult = null
        pendingNotificationPermissionResult?.completeExceptionally(
            CancellationException("Activity was destroyed before notification permission completed.")
        )
        pendingNotificationPermissionResult = null
        if (::runtime.isInitialized && runtime.getActivityDelegate() === activityDelegate) {
            runtime.setActivityDelegate(null)
        }
        if (::runtime.isInitialized &&
            runtime.webExtensionController.getPromptDelegate() === extensionInstaller.promptDelegate
        ) {
            runtime.webExtensionController.setPromptDelegate(null)
        }
        runtime.webExtensionController.setAddonManagerDelegate(null)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            extensionInstaller.cleanup()
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.takeIf { it.isNotBlank() } ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun ensureNotificationChannel(domain: String): String {
        val channelId = "notification_$domain"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                domain,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            notificationManager.createNotificationChannel(channel)
        }
        return channelId
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
                if (!isFinishing && !isDestroyed) {
                    window.decorView.postDelayed(
                        { warmUpWebExtensionController() },
                        1200L
                    )
                }
            },
        )
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

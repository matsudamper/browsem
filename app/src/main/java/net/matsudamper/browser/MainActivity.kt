package net.matsudamper.browser

import android.app.assist.AssistContent
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity() {

    private lateinit var runtime: GeckoRuntime
    private var pendingActivityResult: GeckoResult<Intent>? = null
    private var installPromptState by mutableStateOf<InstallPromptState?>(null)
    private var installFailureMessage by mutableStateOf<String?>(null)
    private var pendingExternalUrl by mutableStateOf<String?>(null)
    private var latestAssistUrl by mutableStateOf<String?>(null)
    private var webExtensionWarmUpCompleted = false
    private var webExtensionWarmUpInProgress = false

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

    private val webExtensionPromptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            return createInstallPromptResult(
                extension = extension,
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
            )
        }
    }

    private val addonManagerDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalling(extension: WebExtension) {
            runOnUiThread {
                installFailureMessage = null
            }
        }

        override fun onInstallationFailed(
            extension: WebExtension?,
            installException: WebExtension.InstallException
        ) {
            runOnUiThread {
                installPromptState?.result?.complete(buildInstallPromptResponse(allow = false))
                installPromptState = null
                installFailureMessage = buildInstallFailureMessage(
                    extension = extension,
                    installException = installException,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExternalUrl = extractExternalUrl(intent)
        latestAssistUrl = pendingExternalUrl
        runtime = GeckoRuntime.getDefault(this)
        runtime.setActivityDelegate(activityDelegate)
        runtime.settings.setExtensionsWebAPIEnabled(true)
        runtime.settings.setLoginAutofillEnabled(true)
        runtime.webExtensionController.setPromptDelegate(webExtensionPromptDelegate)
        runtime.webExtensionController.setAddonManagerDelegate(addonManagerDelegate)
        warmUpWebExtensionController()
        setContent {
            BrowserApp(
                runtime = runtime,
                initialExternalUrl = pendingExternalUrl,
                onExternalUrlConsumed = { consumedUrl ->
                    if (pendingExternalUrl == consumedUrl) {
                        pendingExternalUrl = null
                    }
                },
                onCurrentPageUrlForAssist = { pageUrl ->
                    latestAssistUrl = sanitizeAssistUrl(pageUrl)
                },
                onInstallExtensionRequest = { pageUrl ->
                    installFromCurrentPage(pageUrl)
                }
            )
            installPromptState?.let { prompt ->
                InstallPromptDialog(
                    prompt = prompt,
                    resolveInstallPrompt = { allow ->
                        val pendingPrompt = installPromptState ?: return@InstallPromptDialog
                        installPromptState = null
                        pendingPrompt.result.complete(buildInstallPromptResponse(allow = allow))

                    }
                )
            }
            installFailureMessage?.let { message ->
                InstallFailureDialog(
                    message = message,
                    onDismiss = { installFailureMessage = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val externalUrl = extractExternalUrl(intent) ?: return
        pendingExternalUrl = externalUrl
        latestAssistUrl = externalUrl
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        val assistUrl = sanitizeAssistUrl(latestAssistUrl) ?: return
        outContent.webUri = Uri.parse(assistUrl)
    }

    override fun onProvideAssistData(data: Bundle) {
        super.onProvideAssistData(data)
        val assistUrl = sanitizeAssistUrl(latestAssistUrl) ?: return
        data.putString("url", assistUrl)
        data.putString("android.intent.extra.URL", assistUrl)
    }

    override fun onResume() {
        super.onResume()
        runtime.settings.setLoginAutofillEnabled(true)
        warmUpWebExtensionController()
    }

    override fun onDestroy() {
        installPromptState?.result?.completeExceptionally(
            CancellationException("Activity was destroyed before extension prompt completed.")
        )
        installPromptState = null
        installFailureMessage = null
        pendingActivityResult?.completeExceptionally(
            CancellationException("Activity was destroyed before Gecko activity completed.")
        )
        pendingActivityResult = null
        if (::runtime.isInitialized && runtime.getActivityDelegate() === activityDelegate) {
            runtime.setActivityDelegate(null)
        }
        if (::runtime.isInitialized &&
            runtime.webExtensionController.getPromptDelegate() === webExtensionPromptDelegate
        ) {
            runtime.webExtensionController.setPromptDelegate(null)
        }
        runtime.webExtensionController.setAddonManagerDelegate(null)
        super.onDestroy()
    }

    private fun createInstallPromptResult(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>
    ): GeckoResult<WebExtension.PermissionPromptResponse> {
        val result = GeckoResult<WebExtension.PermissionPromptResponse>()
        runOnUiThread {
            installPromptState?.result?.complete(buildInstallPromptResponse(allow = false))
            installPromptState = InstallPromptState(
                message = buildInstallPromptMessage(
                    extension = extension,
                    permissions = permissions,
                    origins = origins,
                    dataCollectionPermissions = dataCollectionPermissions,
                ),
                result = result,
            )
        }
        return result
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            installPromptState?.result?.complete(
                buildInstallPromptResponse(allow = false)
            )
            installPromptState = null
            installFailureMessage = null
        }
    }

    private fun buildInstallPromptResponse(allow: Boolean): WebExtension.PermissionPromptResponse {
        return WebExtension.PermissionPromptResponse(
            allow,
            false,
            allow,
        )
    }

    private fun buildInstallPromptMessage(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>
    ): String {
        val extensionName = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id
        val details = listOfNotNull(
            formatPromptSection("Permissions", permissions),
            formatPromptSection("Site access", origins),
            formatPromptSection("Data collection", dataCollectionPermissions),
        )
        return buildString {
            append("Install \"")
            append(extensionName)
            append("\"?")
            if (details.isNotEmpty()) {
                append("\n\n")
                append(details.joinToString("\n\n"))
            }
        }
    }

    private fun formatPromptSection(title: String, items: Array<String>): String? {
        if (items.isEmpty()) {
            return null
        }
        return buildString {
            append(title)
            append(":\n")
            items.forEachIndexed { index, item ->
                append("- ")
                append(item)
                if (index != items.lastIndex) {
                    append('\n')
                }
            }
        }
    }

    private fun buildInstallFailureMessage(
        extension: WebExtension?,
        installException: WebExtension.InstallException
    ): String {
        val extensionName = extension?.metaData?.name?.takeIf { it.isNotBlank() }
            ?: installException.extensionName
            ?: extension?.id
            ?: installException.extensionId
            ?: "Unknown extension"
        val reason = when (installException.code) {
            WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE ->
                "This extension is not compatible with GeckoView."

            WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE ->
                "This extension type is not supported."

            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED ->
                "Only signed extensions can be installed."

            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED ->
                "This extension is blocklisted."

            WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED ->
                "This extension is soft-blocked for safety."

            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED ->
                "Installation was canceled."

            else -> "Installation failed (code: ${installException.code})."
        }
        return buildString {
            append("Failed to install \"")
            append(extensionName)
            append("\".\n\n")
            append(reason)
        }
    }

    private fun installFromCurrentPage(pageUrl: String) {
        installFailureMessage = null
        val installUri = resolveAmoInstallUriFromPage(pageUrl)
        if (installUri == null) {
            installFailureMessage =
                "Extension install is available on AMO add-on pages.\n\nCurrent URL:\n$pageUrl"
            return
        }
        runtime.webExtensionController
            .install(
                installUri,
                WebExtensionController.INSTALLATION_METHOD_MANAGER,
            )
            .accept(
                {},
                { throwable ->
                    val error = throwable ?: RuntimeException("Unknown install error.")
                    runOnUiThread {
                        when (error) {
                            is WebExtension.InstallException -> {
                                installFailureMessage = buildInstallFailureMessage(
                                    extension = null,
                                    installException = error,
                                )
                            }

                            else -> {
                                installFailureMessage =
                                    "Extension install failed.\n\n${error.message ?: error::class.java.name}"
                            }
                        }
                    }
                },
            )
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

    private fun extractExternalUrl(intent: Intent?): String? {
        val launchIntent = intent ?: return null
        if (launchIntent.action != Intent.ACTION_VIEW) {
            return null
        }
        val uri = launchIntent.data ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        return uri.toString()
    }

    private fun sanitizeAssistUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) {
            return null
        }
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        return uri.toString()
    }
}


@Composable
private fun InstallPromptDialog(
    prompt: InstallPromptState,
    resolveInstallPrompt: (allow: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            resolveInstallPrompt(false)
        },
        title = {
            Text("Install extension")
        },
        text = {
            Text(prompt.message)
        },
        confirmButton = {
            TextButton(
                onClick = { resolveInstallPrompt(true) }
            ) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { resolveInstallPrompt(false) }
            ) {
                Text("Cancel")
            }
        }
    )
}

private data class InstallPromptState(
    val message: String,
    val result: GeckoResult<WebExtension.PermissionPromptResponse>,
)

@Composable
private fun InstallFailureDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Extension install failed")
        },
        text = {
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

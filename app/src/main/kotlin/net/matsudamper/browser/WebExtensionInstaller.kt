package net.matsudamper.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

internal class WebExtensionInstaller(
    private val runtime: GeckoRuntime,
) {
    var installPromptState by mutableStateOf<InstallPromptState?>(null)
        private set
    var installFailureMessage by mutableStateOf<String?>(null)

    val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            return createInstallPromptResult(
                extension = extension,
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
            )
        }
    }

    val addonManagerDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalling(extension: WebExtension) {
            installFailureMessage = null
        }

        override fun onInstallationFailed(
            extension: WebExtension?,
            installException: WebExtension.InstallException,
        ) {
            installPromptState?.result?.complete(buildInstallPromptResponse(allow = false))
            installPromptState = null
            installFailureMessage = buildInstallFailureMessage(
                extension = extension,
                installException = installException,
            )
        }
    }

    fun installFromCurrentPage(pageUrl: String) {
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
                },
            )
    }

    fun resolveInstallPrompt(allow: Boolean) {
        val pendingPrompt = installPromptState ?: return
        installPromptState = null
        pendingPrompt.result.complete(buildInstallPromptResponse(allow = allow))
    }

    fun dismissInstallFailure() {
        installFailureMessage = null
    }

    fun cleanup() {
        installPromptState?.result?.completeExceptionally(
            java.util.concurrent.CancellationException("Installer was cleaned up.")
        )
        installPromptState = null
        installFailureMessage = null
    }

    private fun createInstallPromptResult(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
    ): GeckoResult<WebExtension.PermissionPromptResponse> {
        val result = GeckoResult<WebExtension.PermissionPromptResponse>()
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
        return result
    }

    private fun buildInstallPromptResponse(allow: Boolean): WebExtension.PermissionPromptResponse {
        return WebExtension.PermissionPromptResponse(allow, false, allow)
    }

    private fun buildInstallPromptMessage(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
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
        if (items.isEmpty()) return null
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
        installException: WebExtension.InstallException,
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
}

internal data class InstallPromptState(
    val message: String,
    val result: GeckoResult<WebExtension.PermissionPromptResponse>,
)

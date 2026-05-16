package net.matsudamper.browser

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

internal const val AD_GUARD_DIAG_TAG = "AdGuardDiag"

internal class WebExtensionInstaller(
    private val runtime: GeckoRuntime,
) {
    var installPromptState by mutableStateOf<InstallPromptState?>(null)
        private set
    var permissionPromptState by mutableStateOf<PermissionPromptState?>(null)
        private set
    var installFailureMessage by mutableStateOf<String?>(null)

    val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            Log.i(
                AD_GUARD_DIAG_TAG,
                "onInstallPromptRequest id=${extension.id} name=${extension.metaData.name} " +
                    "permissions=${permissions.toList()} origins=${origins.toList()} " +
                    "dataCollection=${dataCollectionPermissions.toList()}",
            )
            return createInstallPromptResult(
                extension = extension,
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
            )
        }

        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<String>,
            newOrigins: Array<String>,
            newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> {
            Log.i(
                AD_GUARD_DIAG_TAG,
                "onUpdatePrompt id=${extension.id} newPermissions=${newPermissions.toList()} " +
                    "newOrigins=${newOrigins.toList()} newDataCollection=${newDataCollectionPermissions.toList()}",
            )
            return createPermissionPromptResult(
                title = "Update extension",
                extension = extension,
                permissions = newPermissions,
                origins = newOrigins,
                dataCollectionPermissions = newDataCollectionPermissions,
                requestKind = PermissionRequestKind.UPDATE,
            )
        }

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> {
            Log.i(
                AD_GUARD_DIAG_TAG,
                "onOptionalPrompt id=${extension.id} permissions=${permissions.toList()} " +
                    "origins=${origins.toList()} dataCollection=${dataCollectionPermissions.toList()}",
            )
            return createPermissionPromptResult(
                title = "Allow extension permission",
                extension = extension,
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
                requestKind = PermissionRequestKind.OPTIONAL,
            )
        }
    }

    val extensionProcessDelegate = object : WebExtensionController.ExtensionProcessDelegate {
        override fun onDisabledProcessSpawning() {
            Log.w(
                AD_GUARD_DIAG_TAG,
                "onDisabledProcessSpawning: 拡張プロセスの spawning が無効化されたため再有効化を呼ぶ",
            )
            runtime.webExtensionController.enableExtensionProcessSpawning()
        }
    }

    val addonManagerDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalling(extension: WebExtension) {
            Log.i(AD_GUARD_DIAG_TAG, "AddonManager.onInstalling id=${extension.id} name=${extension.metaData.name}")
            installFailureMessage = null
        }

        override fun onInstalled(extension: WebExtension) {
            logExtensionState("AddonManager.onInstalled", extension)
        }

        override fun onEnabling(extension: WebExtension) {
            logExtensionState("AddonManager.onEnabling", extension)
        }

        override fun onEnabled(extension: WebExtension) {
            logExtensionState("AddonManager.onEnabled", extension)
        }

        override fun onDisabling(extension: WebExtension) {
            logExtensionState("AddonManager.onDisabling", extension)
        }

        override fun onDisabled(extension: WebExtension) {
            logExtensionState("AddonManager.onDisabled", extension)
        }

        override fun onUninstalling(extension: WebExtension) {
            Log.i(AD_GUARD_DIAG_TAG, "AddonManager.onUninstalling id=${extension.id}")
        }

        override fun onUninstalled(extension: WebExtension) {
            Log.i(AD_GUARD_DIAG_TAG, "AddonManager.onUninstalled id=${extension.id}")
        }

        override fun onReady(extension: WebExtension) {
            // onReady は拡張のバックグラウンドスクリプトが起動完了して API 利用可能になった
            // ことを示す。AdGuard の webRequest ハンドラ登録もこのタイミングで完了するはず。
            logExtensionState("AddonManager.onReady", extension)
        }

        override fun onOptionalPermissionsChanged(extension: WebExtension) {
            logExtensionState("AddonManager.onOptionalPermissionsChanged", extension)
        }

        override fun onInstallationFailed(
            extension: WebExtension?,
            installException: WebExtension.InstallException,
        ) {
            Log.w(
                AD_GUARD_DIAG_TAG,
                "AddonManager.onInstallationFailed id=${extension?.id} " +
                    "code=${installException.code} extensionName=${installException.extensionName} " +
                    "message=${installException.message}",
            )
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
        Log.i(AD_GUARD_DIAG_TAG, "installFromCurrentPage pageUrl=$pageUrl installUri=$installUri")
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
                { ext ->
                    Log.i(
                        AD_GUARD_DIAG_TAG,
                        "install accepted id=${ext?.id} name=${ext?.metaData?.name} " +
                            "version=${ext?.metaData?.version} enabled=${ext?.metaData?.enabled} " +
                            "signedState=${ext?.metaData?.signedState} blocklistState=${ext?.metaData?.blocklistState} " +
                            "disabledFlags=${ext?.metaData?.disabledFlags}",
                    )
                },
                { throwable ->
                    val error = throwable ?: RuntimeException("Unknown install error.")
                    Log.w(AD_GUARD_DIAG_TAG, "install rejected uri=$installUri", error)
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

    private fun logExtensionState(label: String, extension: WebExtension) {
        val md = extension.metaData
        Log.i(
            AD_GUARD_DIAG_TAG,
            "$label id=${extension.id} name=${md.name} version=${md.version} " +
                "enabled=${md.enabled} allowedInPrivateBrowsing=${md.allowedInPrivateBrowsing} " +
                "signedState=${md.signedState} blocklistState=${md.blocklistState} " +
                "disabledFlags=${md.disabledFlags} optionsPageUrl=${md.optionsPageUrl} " +
                "requiredPermissions=${md.requiredPermissions.toList()} " +
                "requiredOrigins=${md.requiredOrigins.toList()} " +
                "optionalPermissions=${md.optionalPermissions.toList()} " +
                "grantedOptionalPermissions=${md.grantedOptionalPermissions.toList()} " +
                "optionalOrigins=${md.optionalOrigins.toList()} " +
                "grantedOptionalOrigins=${md.grantedOptionalOrigins.toList()} " +
                "incognito=${md.incognito} temporary=${md.temporary}",
        )
    }

    fun resolveInstallPrompt(allow: Boolean) {
        val pendingPrompt = installPromptState ?: return
        installPromptState = null
        pendingPrompt.result.complete(buildInstallPromptResponse(allow = allow))
    }

    fun resolvePermissionPrompt(allow: Boolean) {
        val pendingPrompt = permissionPromptState ?: return
        permissionPromptState = null
        pendingPrompt.result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
    }

    fun dismissInstallFailure() {
        installFailureMessage = null
    }

    fun cleanup() {
        installPromptState?.result?.completeExceptionally(
            java.util.concurrent.CancellationException("Installer was cleaned up.")
        )
        installPromptState = null
        permissionPromptState?.result?.completeExceptionally(
            java.util.concurrent.CancellationException("Installer was cleaned up.")
        )
        permissionPromptState = null
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

    private fun createPermissionPromptResult(
        title: String,
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
        requestKind: PermissionRequestKind,
    ): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        permissionPromptState?.result?.complete(AllowOrDeny.DENY)
        permissionPromptState = PermissionPromptState(
            title = title,
            message = buildPermissionPromptMessage(
                extension = extension,
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
                requestKind = requestKind,
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

    private fun buildPermissionPromptMessage(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
        requestKind: PermissionRequestKind,
    ): String {
        val extensionName = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id
        val details = listOfNotNull(
            formatPromptSection("Permissions", permissions),
            formatPromptSection("Site access", origins),
            formatPromptSection("Data collection", dataCollectionPermissions),
        )
        val header = when (requestKind) {
            PermissionRequestKind.UPDATE ->
                "\"$extensionName\" requests new permissions for an update."
            PermissionRequestKind.OPTIONAL ->
                "\"$extensionName\" requests additional permissions."
        }
        return buildString {
            append(header)
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

@Stable
internal data class InstallPromptState(
    val message: String,
    val result: GeckoResult<WebExtension.PermissionPromptResponse>,
)

@Stable
internal data class PermissionPromptState(
    val title: String,
    val message: String,
    val result: GeckoResult<AllowOrDeny>,
)

internal enum class PermissionRequestKind {
    UPDATE,
    OPTIONAL,
}

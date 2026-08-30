package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

internal class WebExtensionInstaller(
    private val runtime: GeckoRuntime,
    // 拡張機能が ready / インストール完了したタイミングで通知される。
    // MainActivity がこれを受けて TabDelegate 等を設定する。
    private val onExtensionReady: (WebExtension) -> Unit = {},
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
            return createInstallPromptResult(
                extension = extension,
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
            )
        }

        // 既にインストール済みの拡張がアップデートで新しい権限を要求した際に呼ばれる。
        // 未実装だとデフォルトの null 戻りがアップデート拒否扱いとなり、AdGuard 等の自動更新が
        // 永続的にブロックされる原因となるため実装しておく。
        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<String>,
            newOrigins: Array<String>,
            newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> {
            return createPermissionPromptResult(
                title = "Update extension",
                extension = extension,
                permissions = newPermissions,
                origins = newOrigins,
                dataCollectionPermissions = newDataCollectionPermissions,
                requestKind = PermissionRequestKind.UPDATE,
            )
        }

        // 拡張機能が runtime 中に追加の権限/オリジンを要求する (browser.permissions.request 等)
        // 場合に呼ばれる。MV3 の host_permissions など、ユーザーが個別に許可する権限はここで
        // 要求される。未実装だと拒否扱いとなるため実装しておく。
        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> {
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

    // 拡張プロセスがクラッシュ閾値を超えると Gecko 側で spawning が無効化される。
    // 再有効化しないと再起動しても webRequest 等の API が動かないままになるため、
    // 即時に spawning を有効化する。
    val extensionProcessDelegate = object : WebExtensionController.ExtensionProcessDelegate {
        override fun onDisabledProcessSpawning() {
            runtime.webExtensionController.enableExtensionProcessSpawning()
        }
    }

    val addonManagerDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalling(extension: WebExtension) {
            installFailureMessage = null
        }

        override fun onReady(extension: WebExtension) {
            // onReady は拡張のバックグラウンドスクリプトが起動完了して API 利用可能になった
            // ことを示す。このタイミングで TabDelegate 等を設定する必要がある。
            onExtensionReady(extension)
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
            java.util.concurrent.CancellationException("Installer was cleaned up."),
        )
        installPromptState = null
        permissionPromptState?.result?.completeExceptionally(
            java.util.concurrent.CancellationException("Installer was cleaned up."),
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

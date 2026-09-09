package net.matsudamper.browser.feature.webauthncompat

import java.util.concurrent.TimeoutException
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

sealed interface WebAuthnCompatInstallState {
    data object Pending : WebAuthnCompatInstallState

    data object Installed : WebAuthnCompatInstallState

    data class Failed(val error: Throwable) : WebAuthnCompatInstallState
}

class WebAuthnCompatWebExtension {
    private var installationResult: GeckoResult<WebExtension>? = null
    private var desiredEnabled: Boolean? = null

    fun install(runtime: GeckoRuntime): GeckoResult<WebExtension> {
        val current = installationResult
        if (current != null) return current
        return createInstallation(runtime).also { installationResult = it }
    }

    fun retryInstall(runtime: GeckoRuntime): GeckoResult<WebExtension> {
        val installation = createInstallation(runtime)
        val configured = desiredEnabled?.let { enabled ->
            applyEnabled(runtime, installation, enabled)
        } ?: installation
        return configured.also { installationResult = it }
    }

    fun setEnabled(runtime: GeckoRuntime, enabled: Boolean): GeckoResult<WebExtension> {
        desiredEnabled = enabled
        val installation = installationResult ?: createInstallation(runtime)
        return applyEnabled(runtime, installation, enabled)
            .also { installationResult = it }
    }

    fun retrySetEnabled(runtime: GeckoRuntime, enabled: Boolean): GeckoResult<WebExtension> {
        desiredEnabled = enabled
        return applyEnabled(runtime, createInstallation(runtime), enabled)
            .also { installationResult = it }
    }

    fun installationState(): WebAuthnCompatInstallState {
        val result = installationResult ?: return WebAuthnCompatInstallState.Pending
        return try {
            // 構成変更時に完了済み結果を同期判定できるよう、待機時間 0 で状態だけ確認する。
            result.poll(0)
            WebAuthnCompatInstallState.Installed
        } catch (_: TimeoutException) {
            WebAuthnCompatInstallState.Pending
        } catch (error: Throwable) {
            WebAuthnCompatInstallState.Failed(error)
        }
    }

    private fun applyEnabled(
        runtime: GeckoRuntime,
        installation: GeckoResult<WebExtension>,
        enabled: Boolean,
    ): GeckoResult<WebExtension> {
        return installation.then { extension ->
            val installedExtension = extension
                ?: return@then GeckoResult.fromException(
                    IllegalStateException("WebAuthn 互換拡張機能のインストール結果が null です"),
                )
            if (!enabled) {
                return@then runtime.webExtensionController.disable(
                    installedExtension,
                    WebExtensionController.EnableSource.APP,
                )
            }

            // 以前に汎用の拡張機能画面から USER ソースで無効化されていた場合も、
            // 専用設定を有効にすれば確実に動作する状態へ戻す。
            runtime.webExtensionController
                .enable(installedExtension, WebExtensionController.EnableSource.USER)
                .then { userEnabledExtension ->
                    runtime.webExtensionController.enable(
                        userEnabledExtension ?: installedExtension,
                        WebExtensionController.EnableSource.APP,
                    )
                }
        }
    }

    private fun createInstallation(runtime: GeckoRuntime): GeckoResult<WebExtension> {
        return runtime.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
    }

    companion object {
        const val EXTENSION_ID = "webauthn-compat@browsem"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/webauthn_compat/"
    }
}

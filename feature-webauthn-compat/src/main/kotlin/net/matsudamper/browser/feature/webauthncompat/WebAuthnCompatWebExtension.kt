package net.matsudamper.browser.feature.webauthncompat

import java.util.ArrayDeque
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
    private data class StateRequest(
        val runtime: GeckoRuntime,
        val enabled: Boolean?,
        val installation: GeckoResult<WebExtension>?,
        val result: GeckoResult<WebExtension>,
    )

    private val stateRequestLock = Any()
    private val stateRequests = ArrayDeque<StateRequest>()
    private var stateRequestInProgress = false
    private var installationResult: GeckoResult<WebExtension>? = null
    private var desiredEnabled: Boolean? = null

    fun install(runtime: GeckoRuntime): GeckoResult<WebExtension> {
        return synchronized(stateRequestLock) {
            val current = installationResult
            if (current != null) return@synchronized current
            createInstallation(runtime).also { installationResult = it }
        }
    }

    fun retryInstall(runtime: GeckoRuntime): GeckoResult<WebExtension> {
        val result = GeckoResult<WebExtension>()
        val shouldStart = synchronized(stateRequestLock) {
            stateRequests.addLast(
                StateRequest(
                    runtime = runtime,
                    enabled = desiredEnabled,
                    installation = null,
                    result = result,
                ),
            )
            installationResult = result
            markStateRequestForStartLocked()
        }
        if (shouldStart) {
            startNextStateRequest()
        }
        return result
    }

    fun setEnabled(runtime: GeckoRuntime, enabled: Boolean): GeckoResult<WebExtension> {
        val result = GeckoResult<WebExtension>()
        val shouldStart = synchronized(stateRequestLock) {
            desiredEnabled = enabled
            stateRequests.addLast(
                StateRequest(
                    runtime = runtime,
                    enabled = enabled,
                    installation = installationResult,
                    result = result,
                ),
            )
            installationResult = result
            markStateRequestForStartLocked()
        }
        if (shouldStart) {
            startNextStateRequest()
        }
        return result
    }

    fun retrySetEnabled(runtime: GeckoRuntime, enabled: Boolean): GeckoResult<WebExtension> {
        val result = GeckoResult<WebExtension>()
        val shouldStart = synchronized(stateRequestLock) {
            desiredEnabled = enabled
            stateRequests.addLast(
                StateRequest(
                    runtime = runtime,
                    enabled = enabled,
                    installation = null,
                    result = result,
                ),
            )
            // install() を呼ぶ別の起動経路も、常に最後に要求された状態の完了まで待機させる。
            installationResult = result
            markStateRequestForStartLocked()
        }
        if (shouldStart) {
            startNextStateRequest()
        }
        return result
    }

    fun installationState(): WebAuthnCompatInstallState {
        val result = synchronized(stateRequestLock) {
            installationResult
        } ?: return WebAuthnCompatInstallState.Pending
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

    private fun markStateRequestForStartLocked(): Boolean {
        if (stateRequestInProgress) return false
        stateRequestInProgress = true
        return true
    }

    private fun startNextStateRequest() {
        val request = synchronized(stateRequestLock) {
            stateRequests.firstOrNull()
        } ?: return
        val installation = request.installation ?: createInstallation(request.runtime)
        val operation = request.enabled?.let { enabled ->
            applyEnabled(
                runtime = request.runtime,
                installation = installation,
                enabled = enabled,
            )
        } ?: installation
        operation.accept(
            { extension ->
                if (extension == null) {
                    request.result.completeExceptionally(
                        IllegalStateException("WebAuthn 互換拡張機能の設定結果が null です"),
                    )
                } else {
                    request.result.complete(extension)
                }
                finishStateRequest(request)
            },
            { error ->
                request.result.completeExceptionally(
                    error ?: IllegalStateException("WebAuthn 互換拡張機能の設定に失敗しました"),
                )
                finishStateRequest(request)
            },
        )
    }

    private fun finishStateRequest(request: StateRequest) {
        val hasNext = synchronized(stateRequestLock) {
            check(stateRequests.firstOrNull() === request)
            stateRequests.removeFirst()
            if (stateRequests.isEmpty()) {
                stateRequestInProgress = false
                false
            } else {
                true
            }
        }
        if (hasNext) {
            startNextStateRequest()
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

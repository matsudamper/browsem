package net.matsudamper.browser

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.matsudamper.browser.feature.webauthncompat.WebAuthnCompatWebExtension
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoRuntime

private const val MAX_WEBAUTHN_COMPAT_STARTUP_RETRIES = 5
private const val WEBAUTHN_COMPAT_STARTUP_RETRY_DELAY_MS = 1200L

/**
 * WebAuthn 互換拡張へ保存済み設定が反映されるまで、ページを生成する content を開始しない。
 * 永続的に失敗した場合は MainActivity と同様、規定回数の再試行後に拡張なしで起動を継続する。
 */
@Composable
internal fun WebAuthnCompatStartupGate(
    runtime: GeckoRuntime,
    content: @Composable () -> Unit,
) {
    val webAuthnCompatWebExtension: WebAuthnCompatWebExtension = koinInject()
    var ready by remember(runtime, webAuthnCompatWebExtension) { mutableStateOf(false) }
    LaunchedEffect(runtime, webAuthnCompatWebExtension) {
        var result = webAuthnCompatWebExtension.install(runtime)
        repeat(MAX_WEBAUTHN_COMPAT_STARTUP_RETRIES) { attempt ->
            try {
                result.awaitGecko()
                ready = true
                return@LaunchedEffect
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val retryCount = attempt + 1
                Log.e(
                    "WebAuthnCompatStartupGate",
                    "WebAuthn 互換設定の反映に失敗: $retryCount/$MAX_WEBAUTHN_COMPAT_STARTUP_RETRIES",
                    error,
                )
                if (retryCount >= MAX_WEBAUTHN_COMPAT_STARTUP_RETRIES) {
                    Log.e("WebAuthnCompatStartupGate", "WebAuthn 互換設定の再試行を終了。拡張なしで起動する")
                    ready = true
                    return@LaunchedEffect
                }
                delay(WEBAUTHN_COMPAT_STARTUP_RETRY_DELAY_MS)
                result = webAuthnCompatWebExtension.retryInstall(runtime)
            }
        }
    }

    if (!ready) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    content()
}

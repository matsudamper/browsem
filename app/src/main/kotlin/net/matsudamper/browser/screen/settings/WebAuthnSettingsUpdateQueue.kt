package net.matsudamper.browser.screen.settings

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WebAuthnSettingsUpdate(
    val persist: suspend () -> Unit,
    val onPersisted: () -> Unit,
)

/** onPersisted は開いているセッションの reload を行うため、呼び出し元のスレッドを維持する */
internal suspend fun processWebAuthnSettingsUpdates(
    channel: ReceiveChannel<WebAuthnSettingsUpdate>,
    onError: (Throwable) -> Unit,
) {
    for (update in channel) {
        try {
            withContext(Dispatchers.IO) { update.persist() }
            update.onPersisted()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onError(error)
        }
    }
}

/**
 * WebAuthn 互換設定の保存を直列化する。設定画面より長く生きる必要があるためプロセス寿命のスコープで動かす。
 */
internal class WebAuthnSettingsUpdateQueue(applicationScope: CoroutineScope) {
    private val updates = Channel<WebAuthnSettingsUpdate>(Channel.UNLIMITED)

    init {
        applicationScope.launch {
            processWebAuthnSettingsUpdates(updates) { error ->
                Log.w(TAG, "WebAuthn 互換設定の保存または再読み込みに失敗", error)
            }
        }
    }

    fun enqueue(update: WebAuthnSettingsUpdate) {
        updates.trySend(update)
    }

    private companion object {
        private const val TAG = "WebAuthnSettingsUpdateQueue"
    }
}

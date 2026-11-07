package net.matsudamper.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession

internal class SessionStateWriter(private val tab: BrowserTab) {
    private val states = Channel<GeckoSession.SessionState>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker by lazy {
        scope.launch {
            for (state in states) {
                delay(COALESCE_DELAY_MS)
                val latestState = states.tryReceive().getOrNull() ?: state
                val serialized = withContext(serializationDispatcher) {
                    serializeSessionState(
                        serialize = { latestState.toString() },
                        onRejected = { reason ->
                            Log.w(TAG, "セッション状態の文字列化を中止しました: tabId=${tab.tabId}, $reason")
                        },
                    )
                }
                if (serialized != null) {
                    if (serialized.isEmpty()) {
                        tab.pendingSessionState = null
                    }
                    tab.sessionState = serialized
                }
            }
        }
    }

    fun update(state: GeckoSession.SessionState) {
        if (states.trySend(state).isSuccess) {
            worker.start()
        }
    }

    fun close() {
        states.cancel()
        scope.cancel()
    }

    private companion object {
        private const val TAG = "SessionStateWriter"
        private const val COALESCE_DELAY_MS = 50L

        // 複数タブの巨大な JSON 生成による一時メモリ確保が重ならないようにする。
        private val serializationDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}

internal const val MAX_SESSION_STATE_CHARS = 1024 * 1024

/** 空文字は保存済み履歴の削除、null は前回値の維持を表す。 */
internal fun serializeSessionState(
    serialize: () -> String?,
    onRejected: (String) -> Unit,
): String? {
    val serialized = try {
        serialize()
    } catch (_: OutOfMemoryError) {
        onRejected("reason=OutOfMemoryError, chars=unknown")
        return ""
    } catch (error: RuntimeException) {
        onRejected("reason=${error.javaClass.simpleName}, chars=unknown")
        return ""
    }
    return when {
        serialized.isNullOrBlank() -> {
            onRejected("reason=empty, chars=${serialized?.length}")
            null
        }
        serialized.length > MAX_SESSION_STATE_CHARS -> {
            onRejected("reason=size_limit, chars=${serialized.length}, limit=$MAX_SESSION_STATE_CHARS")
            ""
        }
        else -> serialized
    }
}

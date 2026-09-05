package net.matsudamper.browser.feature.devtools

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 開発者ツール向けのビルトイン WebExtension。
 * コンテンツスクリプトが connectNative でポートを確立し、
 * フォーカスの当たっている入力要素の情報、ページ console 出力、
 * スクリプト実行結果をネイティブ側へ通知する。
 */
class DevToolsWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextRequestId = AtomicLong(0)
    private val nextLogId = AtomicLong(0)

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // セッションごとのフォーカス情報コールバック
    private val sessionCallbacks =
        ConcurrentHashMap<GeckoSession, (FocusedInputInfo?) -> Unit>()

    // セッションごとの console ログ
    private val _consoleLogs = MutableStateFlow<Map<GeckoSession, List<ConsoleLogEntry>>>(emptyMap())
    val consoleLogs: StateFlow<Map<GeckoSession, List<ConsoleLogEntry>>> = _consoleLogs.asStateFlow()

    // スクリプト実行結果の待ち受け
    private val executeCallbacks = ConcurrentHashMap<String, PendingExecution>()

    private data class PendingExecution(
        val session: GeckoSession,
        val port: WebExtension.Port,
        val callback: (ScriptExecutionResult) -> Unit,
    )

    // デリゲートを設定済みのセッションを追跡（二重設定を防ぐ）
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /** フォーカスされている入力要素の情報 */
    data class FocusedInputInfo(
        val id: String,
        val tagName: String,
        val type: String,
        val name: String,
    )

    /** ページ console 出力の 1 行分 */
    data class ConsoleLogEntry(
        val id: Long,
        val level: Level,
        val message: String,
        val url: String,
        val timestampMs: Long,
    ) {
        enum class Level {
            Log,
            Warn,
            Error,
            Info,
            Debug,
        }
    }

    /** スクリプト実行結果 */
    sealed interface ScriptExecutionResult {
        data class Success(val result: String) : ScriptExecutionResult
        data class Failure(val message: String) : ScriptExecutionResult
    }

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    sessionCallbacks.keys.forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    /**
     * セッションを登録し、フォーカス情報を受け取るコールバックを設定する。
     * 入力要素にフォーカスがない場合は null が渡される。
     */
    fun registerSession(
        session: GeckoSession,
        onFocusedInputChanged: (FocusedInputInfo?) -> Unit,
    ) {
        sessionCallbacks[session] = onFocusedInputChanged
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        sessionCallbacks.remove(session)
        sessionPorts.remove(session)
        attachedSessions.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /** 現在フォーカスされている入力要素の情報を問い合わせる */
    fun requestFocusedInput(session: GeckoSession) {
        sendMessage(
            session,
            JSONObject().apply { put("action", "query") },
        )
    }

    /** 指定セッションの console ログを返す */
    fun consoleLogsOf(session: GeckoSession): List<ConsoleLogEntry> =
        _consoleLogs.value[session].orEmpty()

    /** 指定セッションの console ログを消去する */
    fun clearConsoleLogs(session: GeckoSession) {
        _consoleLogs.update { current ->
            current + (session to listOf())
        }
    }

    /**
     * ページコンテキストで JavaScript を実行する。
     * 結果は [onResult] で非同期に返る。
     */
    fun executeScript(
        session: GeckoSession,
        code: String,
        onResult: (ScriptExecutionResult) -> Unit,
    ) {
        val port = sessionPorts[session]
        if (port == null) {
            onResult(ScriptExecutionResult.Failure("ポートが未接続"))
            return
        }
        val requestId = "req-${nextRequestId.incrementAndGet()}"
        executeCallbacks[requestId] = PendingExecution(session, port, onResult)
        port.postMessage(
            JSONObject().apply {
                put("action", "execute")
                put("requestId", requestId)
                put("code", code)
            },
        )
    }

    private fun sendMessage(session: GeckoSession, message: JSONObject) {
        val port = sessionPorts[session]
        if (port == null) {
            Log.w(TAG, "sendMessage: ポートが未接続 action=${message.optString("action")}")
            return
        }
        port.postMessage(message)
    }

    private fun appendConsoleLog(session: GeckoSession, entry: ConsoleLogEntry) {
        _consoleLogs.update { current ->
            val updatedLogs = (current[session].orEmpty() + entry).takeLast(MAX_CONSOLE_LOG_ENTRIES)
            current + (session to updatedLogs)
        }
    }

    private fun createConsoleLogEntry(json: JSONObject): ConsoleLogEntry {
        val rawMessage = json.optString("message", "")
        val message = if (rawMessage.length <= MAX_CONSOLE_MESSAGE_LENGTH) {
            rawMessage
        } else {
            rawMessage.take(MAX_CONSOLE_MESSAGE_LENGTH) + "…"
        }
        return ConsoleLogEntry(
            id = nextLogId.incrementAndGet(),
            level = parseConsoleLevel(json.optString("level")),
            message = message,
            url = json.optString("url", ""),
            timestampMs = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }

    private fun handlePortMessage(session: GeckoSession, json: JSONObject) {
        when (json.optString("action")) {
            "consoleLog" -> {
                val entry = createConsoleLogEntry(json)
                mainHandler.post {
                    appendConsoleLog(session, entry)
                }
            }
            "executeResult" -> {
                val requestId = json.optString("requestId", "")
                val pending = executeCallbacks.remove(requestId) ?: return
                val result = if (json.optBoolean("success", false)) {
                    ScriptExecutionResult.Success(json.optString("result", ""))
                } else {
                    ScriptExecutionResult.Failure(json.optString("error", "実行に失敗しました"))
                }
                mainHandler.post {
                    pending.callback(result)
                }
            }
            else -> {
                val info = if (json.optBoolean("focused", false)) {
                    FocusedInputInfo(
                        id = json.optString("id", ""),
                        tagName = json.optString("tagName", ""),
                        type = json.optString("type", ""),
                        name = json.optString("name", ""),
                    )
                } else {
                    null
                }
                mainHandler.post {
                    sessionCallbacks[session]?.invoke(info)
                }
            }
        }
    }

    private fun parseConsoleLevel(rawLevel: String): ConsoleLogEntry.Level {
        return when (rawLevel.lowercase()) {
            "warn" -> ConsoleLogEntry.Level.Warn
            "error" -> ConsoleLogEntry.Level.Error
            "info" -> ConsoleLogEntry.Level.Info
            "debug" -> ConsoleLogEntry.Level.Debug
            else -> ConsoleLogEntry.Level.Log
        }
    }

    private fun failPendingExecutionsForPort(port: WebExtension.Port, message: String) {
        executeCallbacks.entries.removeIf { (_, pending) ->
            if (pending.port !== port) return@removeIf false
            mainHandler.post {
                pending.callback(ScriptExecutionResult.Failure(message))
            }
            true
        }
    }

    private fun attachSessionDelegate(session: GeckoSession, ext: WebExtension) {
        if (!attachedSessions.add(session)) return
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? = null

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "onConnect: ポート接続")
                    sessionPorts[session] = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port,
                        ) {
                            val json = message as? JSONObject ?: return
                            handlePortMessage(session, json)
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            failPendingExecutionsForPort(port, "ポートが切断されました")
                            // ページ遷移等で新しいポートに差し替わっている場合、
                            // 古いポートの遅延切断が新しい接続を消さないよう識別チェックする
                            if (sessionPorts.remove(session, port)) {
                                mainHandler.post {
                                    sessionCallbacks[session]?.invoke(null)
                                }
                            }
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        private const val TAG = "DevToolsExt"
        private const val NATIVE_APP_ID = "devToolsBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/dev_tools_bridge/"
        private const val MAX_CONSOLE_LOG_ENTRIES = 500
        private const val MAX_CONSOLE_MESSAGE_LENGTH = 4096
    }
}

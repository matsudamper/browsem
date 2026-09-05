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
 * フォーカスの当たっている入力要素の情報とページの console 出力をネイティブ側へ通知する。
 * JavaScript の実行要求はネイティブ側からコンテンツスクリプトへ送る。
 */
class DevToolsWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextEntryId = AtomicLong(0)
    private val nextRequestId = AtomicLong(0)

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // セッションごとのフォーカス情報コールバック
    private val sessionCallbacks =
        ConcurrentHashMap<GeckoSession, (FocusedInputInfo?) -> Unit>()

    // デリゲートを設定済みのセッションを追跡（二重設定を防ぐ）
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // コンソール画面を表示中のセッション。転送はこの間だけ行う
    private val consoleWatchingSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // セッションごとに受信済みのコンテンツスクリプト通番。再送分の重複取り込みを防ぐ
    private val receivedLogSeq = ConcurrentHashMap<GeckoSession, Long>()

    // 実行結果の待ち受け。ポート切断やタイムアウトでも必ず結果を返す
    private val pendingExecutions = ConcurrentHashMap<String, PendingExecution>()

    private val _consoleEntries =
        MutableStateFlow<Map<GeckoSession, List<ConsoleEntry>>>(mapOf())

    /** セッションごとのコンソール表示内容 */
    val consoleEntries: StateFlow<Map<GeckoSession, List<ConsoleEntry>>> =
        _consoleEntries.asStateFlow()

    /** フォーカスされている入力要素の情報 */
    data class FocusedInputInfo(
        val id: String,
        val tagName: String,
        val type: String,
        val name: String,
    )

    /** コンソールに並ぶ 1 行 */
    data class ConsoleEntry(
        val id: Long,
        val kind: Kind,
        val message: String,
        /** 出力元のページ URL。実行入力・実行結果では空文字 */
        val url: String,
        val timestampMs: Long,
    ) {
        enum class Kind {
            Log,
            Info,
            Warn,
            Error,
            Debug,

            /** 実行した JavaScript の入力 */
            Input,

            /** 実行結果 */
            Result,

            /** 実行時のエラー */
            ResultError,
        }
    }

    private class PendingExecution(
        val session: GeckoSession,
        val port: WebExtension.Port,
        val onFinished: () -> Unit,
        val timeout: Runnable,
    )

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
        consoleWatchingSessions.remove(session)
        receivedLogSeq.remove(session)
        _consoleEntries.update { current -> current - session }
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /** 現在フォーカスされている入力要素の情報を問い合わせる */
    fun requestFocusedInput(session: GeckoSession) {
        postToContentScript(session, JSONObject().apply { put("action", "query") })
    }

    /**
     * console 出力の転送を切り替える。
     * コンテンツスクリプトは常時ログを溜めているため、開始時には未受信分がまとめて送られる。
     */
    fun setConsoleWatching(session: GeckoSession, watching: Boolean) {
        if (watching) {
            consoleWatchingSessions.add(session)
        } else {
            consoleWatchingSessions.remove(session)
        }
        postToContentScript(
            session,
            JSONObject().apply {
                put("action", "setConsoleForwarding")
                put("enabled", watching)
                put("sinceSeq", receivedLogSeq[session] ?: 0L)
            },
        )
    }

    /** コンソールの表示内容を消去する */
    fun clearConsoleEntries(session: GeckoSession) {
        _consoleEntries.update { current -> current + (session to listOf()) }
    }

    /**
     * ページで JavaScript を実行する。入力と結果はコンソールの表示内容に追加される。
     * 結果が確定したタイミングで [onFinished] が呼ばれる。
     */
    fun executeScript(session: GeckoSession, code: String, onFinished: () -> Unit) {
        appendEntry(session, ConsoleEntry.Kind.Input, code, url = "")
        val port = sessionPorts[session]
        if (port == null) {
            appendEntry(session, ConsoleEntry.Kind.ResultError, PORT_DISCONNECTED_MESSAGE, url = "")
            onFinished()
            return
        }
        val requestId = "req-${nextRequestId.incrementAndGet()}"
        val timeout = Runnable {
            finishExecution(requestId, ConsoleEntry.Kind.ResultError, "実行がタイムアウトしました")
        }
        pendingExecutions[requestId] = PendingExecution(
            session = session,
            port = port,
            onFinished = onFinished,
            timeout = timeout,
        )
        mainHandler.postDelayed(timeout, EXECUTE_TIMEOUT_MS)
        port.postMessage(
            JSONObject().apply {
                put("action", "execute")
                put("requestId", requestId)
                put("code", code)
            },
        )
    }

    private fun postToContentScript(session: GeckoSession, message: JSONObject) {
        val port = sessionPorts[session]
        if (port == null) {
            Log.w(TAG, "ポートが未接続: action=${message.optString("action")}")
            return
        }
        port.postMessage(message)
    }

    private fun appendEntry(
        session: GeckoSession,
        kind: ConsoleEntry.Kind,
        message: String,
        url: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val entry = ConsoleEntry(
            id = nextEntryId.incrementAndGet(),
            kind = kind,
            message = message,
            url = url,
            timestampMs = timestampMs,
        )
        _consoleEntries.update { current ->
            val entries = (current[session].orEmpty() + entry).takeLast(MAX_CONSOLE_ENTRIES)
            current + (session to entries)
        }
    }

    private fun finishExecution(requestId: String, kind: ConsoleEntry.Kind, message: String) {
        val pending = pendingExecutions.remove(requestId) ?: return
        mainHandler.removeCallbacks(pending.timeout)
        appendEntry(pending.session, kind, message, url = "")
        pending.onFinished()
    }

    private fun handleConsoleLog(session: GeckoSession, json: JSONObject) {
        if (!consoleWatchingSessions.contains(session)) return
        val seq = json.optLong("seq", 0L)
        if (seq <= (receivedLogSeq[session] ?: 0L)) return
        receivedLogSeq[session] = seq
        appendEntry(
            session = session,
            kind = parseLogKind(json.optString("level")),
            message = json.optString("message", ""),
            url = json.optString("url", ""),
            timestampMs = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }

    private fun handleFocusedInput(session: GeckoSession, json: JSONObject) {
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
        sessionCallbacks[session]?.invoke(info)
    }

    private fun handlePortMessage(session: GeckoSession, json: JSONObject) {
        when (json.optString("action")) {
            "consoleLog" -> handleConsoleLog(session, json)

            "executeResult" -> {
                val requestId = json.optString("requestId", "")
                if (json.optBoolean("success", false)) {
                    finishExecution(
                        requestId = requestId,
                        kind = ConsoleEntry.Kind.Result,
                        message = json.optString("result", ""),
                    )
                } else {
                    finishExecution(
                        requestId = requestId,
                        kind = ConsoleEntry.Kind.ResultError,
                        message = json.optString("error", "実行に失敗しました"),
                    )
                }
            }

            else -> handleFocusedInput(session, json)
        }
    }

    private fun parseLogKind(level: String): ConsoleEntry.Kind {
        return when (level) {
            "info" -> ConsoleEntry.Kind.Info
            "warn" -> ConsoleEntry.Kind.Warn
            "error" -> ConsoleEntry.Kind.Error
            "debug" -> ConsoleEntry.Kind.Debug
            else -> ConsoleEntry.Kind.Log
        }
    }

    /** ページ遷移でコンテンツスクリプトが作り直されるため、そのページ分のログを取り直す */
    private fun onPortConnected(session: GeckoSession, port: WebExtension.Port) {
        sessionPorts[session] = port
        receivedLogSeq[session] = 0L
        if (consoleWatchingSessions.contains(session)) {
            setConsoleWatching(session, true)
        }
    }

    private fun onPortDisconnected(session: GeckoSession, port: WebExtension.Port) {
        pendingExecutions.keys.toList().forEach { requestId ->
            if (pendingExecutions[requestId]?.port === port) {
                finishExecution(
                    requestId = requestId,
                    kind = ConsoleEntry.Kind.ResultError,
                    message = PORT_DISCONNECTED_MESSAGE,
                )
            }
        }
        // ページ遷移等で新しいポートに差し替わっている場合、
        // 古いポートの遅延切断が新しい接続を消さないよう識別チェックする
        if (sessionPorts.remove(session, port)) {
            sessionCallbacks[session]?.invoke(null)
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
                    mainHandler.post { onPortConnected(session, port) }
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port,
                        ) {
                            val json = message as? JSONObject ?: return
                            mainHandler.post { handlePortMessage(session, json) }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            mainHandler.post { onPortDisconnected(session, port) }
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
        private const val MAX_CONSOLE_ENTRIES = 500
        private const val EXECUTE_TIMEOUT_MS = 10_000L
        private const val PORT_DISCONNECTED_MESSAGE = "ページと接続できていません"
    }
}

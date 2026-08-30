package net.matsudamper.browser.feature.findinpage

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 正規表現対応のページ内検索を提供するビルトイン WebExtension。
 * コンテンツスクリプトが connectNative でポートを確立し、
 * ネイティブ側から検索コマンドを送るとマッチ位置が返ってくる。
 */
class FindInPageWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // セッションごとの最後の search コマンド（ポート再接続時に再送するために保持）
    private val activeSearchCommands = ConcurrentHashMap<GeckoSession, JSONObject>()

    // セッションごとの結果コールバック（current, total, error）
    private val sessionCallbacks =
        ConcurrentHashMap<GeckoSession, (current: Int, total: Int, error: String?) -> Unit>()

    // デリゲートを設定済みのセッションを追跡（二重設定を防ぐ）
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

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
     * セッションを登録し、検索結果を受け取るコールバックを設定する。
     * error は "invalid_regex" など。null のときは正常結果。
     */
    fun registerSession(
        session: GeckoSession,
        onResult: (current: Int, total: Int, error: String?) -> Unit,
    ) {
        sessionCallbacks[session] = onResult
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        sessionCallbacks.remove(session)
        sessionPorts.remove(session)
        activeSearchCommands.remove(session)
        attachedSessions.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /** 新しい検索クエリを送信する */
    fun search(session: GeckoSession, query: String, isRegex: Boolean) {
        val message = JSONObject().apply {
            put("action", "search")
            put("query", query)
            put("isRegex", isRegex)
        }
        // ポート再接続時に再送できるよう保持する
        activeSearchCommands[session] = JSONObject(message.toString())
        sendMessage(session, message)
    }

    /** 次のマッチへ移動する */
    fun findNext(session: GeckoSession) {
        sendMessage(
            session,
            JSONObject().apply {
                put("action", "next")
            },
        )
    }

    /** 前のマッチへ移動する */
    fun findPrevious(session: GeckoSession) {
        sendMessage(
            session,
            JSONObject().apply {
                put("action", "previous")
            },
        )
    }

    /** ハイライトを全てクリアする */
    fun clear(session: GeckoSession) {
        activeSearchCommands.remove(session)
        sendMessage(
            session,
            JSONObject().apply {
                put("action", "clear")
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
                    // ページ遷移等でポートが再接続された場合、最後の検索コマンドを再送する
                    activeSearchCommands[session]?.let { port.postMessage(it) }
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port,
                        ) {
                            val json = message as? JSONObject ?: return
                            Log.d(TAG, "onPortMessage: $json")
                            val current = json.optInt("current", 0)
                            val total = json.optInt("total", 0)
                            val error = json.optString("error", "").takeUnless { it.isEmpty() }
                            mainHandler.post {
                                sessionCallbacks[session]?.invoke(current, total, error)
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            sessionPorts.remove(session)
                            // 切断時は件数をリセットして UI に古い結果が残らないようにする
                            mainHandler.post {
                                sessionCallbacks[session]?.invoke(0, 0, null)
                            }
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        private const val TAG = "FindInPageExt"
        private const val NATIVE_APP_ID = "findInPageBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/find_in_page_bridge/"
    }
}

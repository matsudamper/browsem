package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * chrome.cast API シムとネイティブ Cast SDK を橋渡しするビルトイン WebExtension。
 * コンテンツスクリプトがポートを確立し、ページ内の chrome.cast 呼び出しを
 * ネイティブ側の CastBridgeHandler に転送する。
 */
class CastWebExtension {
    /**
     * ウェブページからの Cast 操作を処理するハンドラインターフェース。
     * app モジュールの CastManager がこれを実装する。
     */
    interface CastBridgeHandler {
        /** デバイス選択ダイアログを表示してセッションを開始する */
        fun requestSession(callback: (success: Boolean, sessionId: String, deviceName: String) -> Unit)
        /** Cast デバイスにメッセージを送信する */
        fun sendMessage(namespace: String, message: String)
        /** メッセージリスナーを登録する */
        fun addMessageListener(namespace: String, callback: (namespace: String, message: String) -> Unit)
        /** キャストセッションを停止する */
        fun stopSession()
    }

    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()
    // セッションごとのハンドラ
    private val sessionHandlers = ConcurrentHashMap<GeckoSession, CastBridgeHandler>()
    // デリゲート設定済みセッション
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id}")
                    if (ext == null) return@accept
                    extension = ext
                    sessionHandlers.keys.forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    /**
     * セッションを登録し、Cast 操作のハンドラを設定する。
     */
    fun registerSession(session: GeckoSession, handler: CastBridgeHandler) {
        sessionHandlers[session] = handler
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        sessionHandlers.remove(session)
        sessionPorts.remove(session)
        attachedSessions.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /**
     * セッション終了をコンテンツスクリプトに通知する。
     */
    fun notifySessionEnded(session: GeckoSession) {
        val port = sessionPorts[session] ?: return
        mainHandler.post {
            port.postMessage(JSONObject().apply {
                put("action", "sessionEnded")
            })
        }
    }

    /**
     * Cast デバイスから受信したメッセージをコンテンツスクリプトに転送する。
     */
    fun notifyMessageReceived(session: GeckoSession, namespace: String, message: String) {
        val port = sessionPorts[session] ?: return
        mainHandler.post {
            port.postMessage(JSONObject().apply {
                put("action", "messageReceived")
                put("namespace", namespace)
                put("message", message)
            })
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
                            val action = json.optString("action", "")
                            Log.d(TAG, "onPortMessage: action=$action")
                            val handler = sessionHandlers[session] ?: return
                            mainHandler.post {
                                handleAction(session, handler, action, json, port)
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            sessionPorts.remove(session)
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    private fun handleAction(
        session: GeckoSession,
        handler: CastBridgeHandler,
        action: String,
        json: JSONObject,
        port: WebExtension.Port,
    ) {
        when (action) {
            "requestSession" -> {
                handler.requestSession { success, sessionId, deviceName ->
                    mainHandler.post {
                        port.postMessage(JSONObject().apply {
                            put("action", "sessionResult")
                            put("success", success)
                            put("sessionId", sessionId)
                            put("deviceName", deviceName)
                            if (!success) {
                                put("errorCode", "cancel")
                            }
                        })
                    }
                }
            }
            "sendMessage" -> {
                val namespace = json.optString("namespace", "")
                val message = json.optString("message", "")
                handler.sendMessage(namespace, message)
            }
            "addMessageListener" -> {
                val namespace = json.optString("namespace", "")
                handler.addMessageListener(namespace) { ns, msg ->
                    notifyMessageReceived(session, ns, msg)
                }
            }
            "stopSession" -> {
                handler.stopSession()
            }
            "loadMedia" -> {
                // メディアロードはsendMessageのurn:x-cast:com.google.cast.media経由で
                // 処理されるため、ここでは追加処理不要
                Log.d(TAG, "loadMedia: contentId=${json.optString("contentId")}")
            }
            else -> {
                Log.d(TAG, "未知のアクション: $action")
            }
        }
    }

    companion object {
        private const val TAG = "CastWebExtension"
        private const val NATIVE_APP_ID = "castBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/cast_bridge/"
    }
}

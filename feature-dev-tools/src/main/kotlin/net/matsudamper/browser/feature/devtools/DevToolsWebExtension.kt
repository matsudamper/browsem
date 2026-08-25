package net.matsudamper.browser.feature.devtools

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
 * 開発者ツール向けのビルトイン WebExtension。
 * コンテンツスクリプトが connectNative でポートを確立し、
 * フォーカスの当たっている入力要素の情報をネイティブ側へ通知する。
 */
class DevToolsWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // セッションごとのフォーカス情報コールバック
    private val sessionCallbacks =
        ConcurrentHashMap<GeckoSession, (FocusedInputInfo?) -> Unit>()

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
        val port = sessionPorts[session]
        if (port == null) {
            Log.w(TAG, "requestFocusedInput: ポートが未接続")
            return
        }
        port.postMessage(JSONObject().apply { put("action", "query") })
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

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
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
    }
}

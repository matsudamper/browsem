package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

/**
 * mozilla/readability を使って記事を抽出するビルトイン WebExtension。
 * コンテンツスクリプトが connectNative でポートを確立し、
 * ネイティブ側から {action: "extract"} を送ると記事データが返ってくる。
 */
class ReadabilityWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // セッションごとの接続ポート（コンテンツスクリプトが connectNative した時点で格納）
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // セッションごとの結果コールバック（registerSession で登録）
    private val sessionCallbacks = ConcurrentHashMap<GeckoSession, (ReadabilityArticle) -> Unit>()

    // デリゲートを設定済みのセッションを追跡（install と registerSession の競合で二重設定を防ぐ）
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
     * セッションを登録し、記事抽出完了時に呼ばれるコールバックを設定する。
     */
    fun registerSession(session: GeckoSession, onArticle: (ReadabilityArticle) -> Unit) {
        sessionCallbacks[session] = onArticle
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun isInstalled(): Boolean = extension != null

    fun unregisterSession(session: GeckoSession) {
        sessionCallbacks.remove(session)
        sessionPorts.remove(session)
        attachedSessions.remove(session)
        // メッセージデリゲートを解除して、セッション再利用時に onConnect が空振りしないようにする
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /**
     * 指定セッションのコンテンツスクリプトに記事抽出を要求する。
     * ポートが未接続の場合（ページ読み込み中など）は何もしない。
     */
    fun requestExtraction(session: GeckoSession) {
        val port = sessionPorts[session]
        if (port == null) {
            Log.w(TAG, "requestExtraction: ポートが未接続 (ページ読み込み中の可能性)")
            return
        }
        Log.d(TAG, "requestExtraction: {action: extract} を送信")
        port.postMessage(JSONObject().apply { put("action", "extract") })
    }

    private fun attachSessionDelegate(session: GeckoSession, ext: WebExtension) {
        // install() と registerSession() が同時に呼ばれた場合の二重設定を防ぐ
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
                            Log.d(TAG, "onPortMessage: $json")
                            if (!json.optBoolean("success", false)) {
                                Log.w(TAG, "抽出失敗: ${json.optString("error")}")
                                return
                            }
                            val article = ReadabilityArticle(
                                title = json.optString("title", ""),
                                byline = json.optString("byline", "").takeUnless { it.isEmpty() },
                                content = json.optString("content", ""),
                            )
                            mainHandler.post {
                                sessionCallbacks[session]?.invoke(article)
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

    companion object {
        private const val TAG = "ReadabilityExt"
        private const val NATIVE_APP_ID = "readabilityBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/readability_bridge/"
    }
}

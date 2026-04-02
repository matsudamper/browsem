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
 * バックグラウンド再生を維持するための組み込み WebExtension。
 * Page Visibility API を偽装し、アプリがバックグラウンドに移行しても
 * YouTube 等の動画再生が継続できるようにする。
 * 許可ドメインは [updateAllowedDomains] で更新でき、サブドメインにも一致する。
 */
class BackgroundPlaybackWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // セッションごとの最後に受信したホスト名（設定変更時の再送に使用）
    private val sessionHostnames = ConcurrentHashMap<GeckoSession, String>()

    // 現在の許可ドメインセット
    @Volatile
    private var allowedDomains: Set<String> = emptySet()

    // registerSession 呼び出し済みのセッション（extension インストール前の登録を保持）
    private val registeredSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // setMessageDelegate 設定済みのセッション（二重設定を防ぐ）
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
                    registeredSessions.toList().forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    fun registerSession(session: GeckoSession) {
        registeredSessions.add(session)
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        registeredSessions.remove(session)
        attachedSessions.remove(session)
        sessionPorts.remove(session)
        sessionHostnames.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /**
     * 許可ドメインリストを更新する。
     * 登録済みの全セッションに対して即座に有効/無効を再送信する。
     */
    fun updateAllowedDomains(domains: List<String>) {
        allowedDomains = domains.toSet()
        mainHandler.post {
            sessionPorts.forEach { (session, port) ->
                val hostname = sessionHostnames[session] ?: return@forEach
                try {
                    port.postMessage(JSONObject().apply {
                        put("enabled", isAllowed(hostname))
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "updateAllowedDomains: メッセージ送信失敗 hostname=$hostname", e)
                }
            }
        }
    }

    /**
     * hostname がドメインリストに一致するかを判定する。
     * 完全一致またはサブドメインに一致する場合に true を返す。
     * 例: "youtube.com" は "www.youtube.com" にも一致する。
     */
    private fun isAllowed(hostname: String): Boolean {
        return allowedDomains.any { domain ->
            hostname == domain || hostname.endsWith(".$domain")
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
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            val hostname = json.optString("hostname")
                                .takeUnless { it.isEmpty() } ?: return
                            sessionHostnames[session] = hostname
                            Log.d(TAG, "onPortMessage: hostname=$hostname allowed=${isAllowed(hostname)}")
                            mainHandler.post {
                                try {
                                    port.postMessage(JSONObject().apply {
                                        put("enabled", isAllowed(hostname))
                                    })
                                } catch (e: Exception) {
                                    Log.w(TAG, "onPortMessage: 応答送信失敗", e)
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            if (sessionPorts[session] === port) {
                                sessionPorts.remove(session)
                            }
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        private const val TAG = "BackgroundPlaybackExt"
        private const val NATIVE_APP_ID = "backgroundPlaybackBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/background_playback_bridge/"
    }
}

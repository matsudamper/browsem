package net.matsudamper.browser

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
 * 現在のページのレンダリング済み DOM (outerHTML) をダンプするビルトイン WebExtension。
 * サイト固有の不具合調査で実際の DOM 構造を確認するために使用する。
 *
 * コンテンツスクリプトが connectNative でポートを確立し、ネイティブ側から
 * dump コマンドを送るとチャンク分割された HTML が返ってくる。
 */
class DomDumpWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()
    private val registeredSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingDumps = ConcurrentHashMap<GeckoSession, PendingDump>()

    private class PendingDump(
        val onResult: (Result<String>) -> Unit,
    ) {
        val builder = StringBuilder()
    }

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    if (ext == null) return@accept
                    extension = ext
                    registeredSessions.forEach { session ->
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
        sessionPorts.remove(session)
        attachedSessions.remove(session)
        pendingDumps.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /**
     * 現在のページの DOM を取得する。コールバックはメインスレッドで呼ばれる。
     */
    fun requestDump(session: GeckoSession, onResult: (Result<String>) -> Unit) {
        val port = sessionPorts[session]
        if (port == null) {
            onResult(
                Result.failure(
                    IllegalStateException("ページに接続できません。読み込み完了後に再試行してください"),
                ),
            )
            return
        }
        pendingDumps.put(session, PendingDump(onResult))?.also { previous ->
            mainHandler.post {
                previous.onResult(Result.failure(IllegalStateException("新しいダンプ要求で中断されました")))
            }
        }
        port.postMessage(JSONObject().put("action", "dump"))
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

    private fun handlePortMessage(session: GeckoSession, json: JSONObject) {
        val pending = pendingDumps[session] ?: return
        when (json.optString("type")) {
            "chunk" -> {
                pending.builder.append(json.optString("data"))
                val index = json.optInt("index", 0)
                val total = json.optInt("total", 1)
                if (index >= total - 1) {
                    pendingDumps.remove(session)
                    val html = pending.builder.toString()
                    mainHandler.post { pending.onResult(Result.success(html)) }
                }
            }
            "error" -> {
                pendingDumps.remove(session)
                val message = json.optString("message")
                mainHandler.post {
                    pending.onResult(Result.failure(RuntimeException(message)))
                }
            }
        }
    }

    companion object {
        private const val TAG = "DomDumpExt"
        private const val NATIVE_APP_ID = "domDumpBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/dom_dump_bridge/"
    }
}

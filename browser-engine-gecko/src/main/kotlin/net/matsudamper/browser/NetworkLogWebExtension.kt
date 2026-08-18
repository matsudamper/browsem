package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ページの通信を記録するビルトイン WebExtension。
 *
 * バックグラウンドスクリプトが webRequest で全リクエストを収集し、
 * 拡張レベルのポート (networkLogBridge) でネイティブへ通知する。
 * コンテンツスクリプトはセッションレベルのポート (networkLogTabBridge) で
 * 自分の tabId を通知し、webRequest の tabId と GeckoSession を対応付ける。
 */
class NetworkLogWebExtension(
    private val store: NetworkLogStore,
) {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // バックグラウンドスクリプトとのポート
    private var backgroundPort: WebExtension.Port? = null

    // セッションごとの webRequest 上の tabId。
    // 対応付けは非同期に確定するため、UI が購読できるよう Flow で公開する
    private val _sessionTabIds = MutableStateFlow<Map<GeckoSession, Int>>(emptyMap())
    val sessionTabIds: StateFlow<Map<GeckoSession, Int>> = _sessionTabIds.asStateFlow()

    // 登録済みセッション（拡張のインストール完了後にデリゲートを張るために保持）
    private val registeredSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // デリゲートを設定済みのセッション（二重設定を防ぐ）
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // 本文取得の待ち受けコールバック
    private val bodyCallbacks = ConcurrentHashMap<String, (NetworkLogBody) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    attachBackgroundDelegate(ext)
                    registeredSessions.forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    /** セッションを登録し、tabId の通知を受け取れるようにする */
    fun registerSession(session: GeckoSession) {
        registeredSessions.add(session)
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        registeredSessions.remove(session)
        attachedSessions.remove(session)
        _sessionTabIds.update { it - session }
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, TAB_NATIVE_APP_ID)
        }
    }

    /** セッションに対応する webRequest 上の tabId。未取得の場合は null */
    fun tabIdOf(session: GeckoSession): Int? = _sessionTabIds.value[session]

    /**
     * プレビュー用にレスポンス本文を取得する。
     * 通信時の本文は保持していないため、拡張機能側で HTTP キャッシュから再取得する。
     */
    fun requestBody(
        requestId: String,
        url: String,
        onResult: (NetworkLogBody) -> Unit,
    ) {
        val port = backgroundPort
        if (port == null) {
            Log.w(TAG, "requestBody: バックグラウンドポートが未接続")
            onResult(NetworkLogBody.Failure(NetworkLogBody.Failure.Reason.Unavailable))
            return
        }
        bodyCallbacks[requestId] = onResult
        runCatching {
            port.postMessage(
                JSONObject().apply {
                    put("action", "fetchBody")
                    put("requestId", requestId)
                    put("url", url)
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "requestBody: 送信に失敗", error)
            bodyCallbacks.remove(requestId)
            onResult(NetworkLogBody.Failure(NetworkLogBody.Failure.Reason.Unavailable))
        }
    }

    private fun attachBackgroundDelegate(ext: WebExtension) {
        ext.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? = null

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "バックグラウンドポート接続")
                    backgroundPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            when (json.optString("action")) {
                                "entries" -> handleEntries(json.optJSONArray("entries"))
                                "body" -> handleBody(json)
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "バックグラウンドポート切断")
                            if (backgroundPort === port) {
                                backgroundPort = null
                            }
                        }
                    })
                }
            },
            BACKGROUND_NATIVE_APP_ID,
        )
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
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            if (json.optString("action") != "tabId") return
                            val tabId = json.optInt("tabId", -1)
                            if (tabId < 0) return
                            _sessionTabIds.update { it + (session to tabId) }
                        }

                        override fun onDisconnect(port: WebExtension.Port) = Unit
                    })
                }
            },
            TAB_NATIVE_APP_ID,
        )
    }

    private fun handleEntries(array: JSONArray?) {
        if (array == null || array.length() == 0) return
        val entries = buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(json.toNetworkLogEntry())
            }
        }
        mainHandler.post {
            store.record(entries)
        }
    }

    private fun handleBody(json: JSONObject) {
        val requestId = json.optString("requestId").takeIf { it.isNotEmpty() } ?: return
        val callback = bodyCallbacks.remove(requestId) ?: return
        val body = json.toNetworkLogBody()
        mainHandler.post {
            callback(body)
        }
    }

    private fun JSONObject.toNetworkLogEntry(): NetworkLogEntry {
        return NetworkLogEntry(
            requestId = optString("requestId"),
            tabId = optInt("tabId", -1),
            url = optString("url"),
            method = optString("method"),
            resourceType = NetworkResourceType.fromWebRequestType(optString("type")),
            statusCode = optInt("statusCode", 0),
            mimeType = optString("mimeType"),
            startedAtMillis = optDouble("startedAt", 0.0).toLong(),
            durationMillis = optLong("durationMillis", 0),
            transferredBytes = optLong("transferred", 0),
            contentLengthBytes = optLong("contentLength", -1),
            fromCache = optBoolean("fromCache", false),
            error = optString("error").takeIf { it.isNotEmpty() && it != "null" },
            requestHeaders = optJSONArray("requestHeaders").toHeaders(),
            responseHeaders = optJSONArray("responseHeaders").toHeaders(),
        )
    }

    private fun JSONArray?.toHeaders(): List<NetworkLogHeader> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                add(
                    NetworkLogHeader(
                        name = json.optString("name"),
                        value = json.optString("value"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.toNetworkLogBody(): NetworkLogBody {
        if (!optBoolean("ok", false)) {
            val reason = when (optString("reason")) {
                "too_large" -> NetworkLogBody.Failure.Reason.TooLarge
                "fetch_failed" -> NetworkLogBody.Failure.Reason.FetchFailed
                else -> NetworkLogBody.Failure.Reason.Unavailable
            }
            return NetworkLogBody.Failure(reason = reason, sizeBytes = optLong("size", -1))
        }
        val mimeType = optString("mimeType")
        val size = optLong("size", -1)
        return if (optString("kind") == "text") {
            NetworkLogBody.Text(
                text = optString("text"),
                mimeType = mimeType,
                sizeBytes = size,
            )
        } else {
            val bytes = runCatching {
                Base64.decode(optString("base64"), Base64.DEFAULT)
            }.getOrElse { error ->
                Log.w(TAG, "base64 のデコードに失敗", error)
                return NetworkLogBody.Failure(NetworkLogBody.Failure.Reason.FetchFailed, size)
            }
            NetworkLogBody.Binary(
                bytes = bytes,
                mimeType = mimeType,
                sizeBytes = size,
            )
        }
    }

    companion object {
        private const val TAG = "NetworkLogExt"
        private const val BACKGROUND_NATIVE_APP_ID = "networkLogBridge"
        private const val TAB_NATIVE_APP_ID = "networkLogTabBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/network_log_bridge/"
    }
}

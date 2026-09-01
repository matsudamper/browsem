package net.matsudamper.browser.feature.websharefiles

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Web Share API v2 (files) のワークアラウンド用ビルトイン WebExtension。
 * ページ側ポリフィルが送るファイル共有要求を受け取り、OS 共有シート起動へ渡す。
 */
class WebShareFilesWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks =
        ConcurrentHashMap<GeckoSession, (WebShareFilesRequest, GeckoResult<Any>) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    callbacks.keys.forEach { session ->
                        attachSessionMessageDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    fun registerSession(
        session: GeckoSession,
        callback: (WebShareFilesRequest, GeckoResult<Any>) -> Unit,
    ) {
        callbacks[session] = callback
        extension?.also { ext ->
            attachSessionMessageDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        callbacks.remove(session)
    }

    fun cleanup() {
        callbacks.clear()
    }

    private fun attachSessionMessageDelegate(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val json = message as? JSONObject ?: return null
                    val request = json.toWebShareFilesRequest() ?: return null
                    val result = GeckoResult<Any>()
                    mainHandler.post {
                        callbacks[session]?.invoke(request, result)
                            ?: result.complete(
                                JSONObject()
                                    .put("success", false)
                                    .put("error", "共有ハンドラが未登録です")
                                    .put("errorName", "AbortError"),
                            )
                    }
                    return result
                }
            },
            NATIVE_APP_ID,
        )
    }

    data class WebShareFile(
        val name: String,
        val mimeType: String,
        val base64Data: String,
    )

    data class WebShareFilesRequest(
        val requestId: String,
        val title: String,
        val text: String,
        val url: String,
        val files: List<WebShareFile>,
    )

    companion object {
        private const val TAG = "WebShareFilesExt"
        private const val NATIVE_APP_ID = "webShareFilesBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/web_share_files_bridge/"
    }
}

internal fun JSONObject.toWebShareFilesRequest(): WebShareFilesWebExtension.WebShareFilesRequest? {
    val requestId = optString("requestId", "").trim()
    if (requestId.isEmpty()) return null
    val filesJson = optJSONArray("files") ?: JSONArray()
    val files = buildList {
        for (index in 0 until filesJson.length()) {
            val fileJson = filesJson.optJSONObject(index) ?: continue
            val base64Data = fileJson.optString("data", "")
            if (base64Data.isEmpty()) continue
            add(
                WebShareFilesWebExtension.WebShareFile(
                    name = fileJson.optString("name", "shared"),
                    mimeType = fileJson.optString("type", "application/octet-stream"),
                    base64Data = base64Data,
                ),
            )
        }
    }
    if (files.isEmpty()) return null
    return WebShareFilesWebExtension.WebShareFilesRequest(
        requestId = requestId,
        title = optString("title", ""),
        text = optString("text", ""),
        url = optString("url", ""),
        files = files,
    )
}

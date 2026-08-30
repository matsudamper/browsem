package net.matsudamper.browser.feature.twittershare

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Twitter/X の共有リンク・共有ボタンのクリックを捕捉し、
 * OS の共有シートで共有するためのビルトイン WebExtension。
 * コンテンツスクリプトが共有内容を sendNativeMessage で送信する。
 * ThemeColorWebExtension と同様、コンテンツスクリプトからのメッセージは
 * セッションレベルの setMessageDelegate に届く。
 */
class TwitterShareWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<GeckoSession, (TwitterShareData) -> Unit>()

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

    fun registerSession(session: GeckoSession, callback: (TwitterShareData) -> Unit) {
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
                    val data = TwitterShareData(
                        text = json.optString("text", ""),
                        url = json.optString("url", ""),
                        hashtags = json.optString("hashtags", ""),
                        via = json.optString("via", ""),
                    )
                    mainHandler.post {
                        callbacks[session]?.invoke(data)
                    }
                    return null
                }
            },
            NATIVE_APP_ID,
        )
    }

    /** Twitter/X の共有インテントから取り出した共有内容 */
    data class TwitterShareData(
        val text: String,
        val url: String,
        val hashtags: String,
        val via: String,
    ) {
        /** OS 共有シート（text/plain）で共有する 1 つの文字列を組み立てる */
        fun toShareText(): String {
            val hashtagText = hashtags
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ") { "#$it" }
            val viaText = via.trim().takeIf { it.isNotEmpty() }?.let { "via @$it" }
            // 本文・ハッシュタグ・via をスペースで連結し、URL は改行して付与する
            val body = listOf(text.trim(), hashtagText, viaText.orEmpty())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return listOf(body, url.trim())
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
    }

    companion object {
        private const val TAG = "TwitterShareExt"
        private const val NATIVE_APP_ID = "twitterShareBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/twitter_share_bridge/"
    }
}

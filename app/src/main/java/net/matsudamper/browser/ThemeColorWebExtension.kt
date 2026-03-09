package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap

/**
 * theme-colorメタタグを取得するためのビルトインWebExtension。
 * GeckoViewではコンテンツスクリプトのsendNativeMessageは
 * session.webExtensionController.setMessageDelegate（セッションレベル）に届く。
 * ext.setMessageDelegate（拡張レベル）はバックグラウンドスクリプト専用。
 */
internal class ThemeColorWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<GeckoSession, (Color?, String) -> Unit>()

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

    fun registerSession(session: GeckoSession, callback: (Color?, String) -> Unit) {
        callbacks[session] = callback
        extension?.also { ext ->
            attachSessionMessageDelegate(session, ext)
        }
    }

    fun isInstalled(): Boolean = extension != null

    fun cleanup() {
        callbacks.clear()
    }

    fun unregisterSession(session: GeckoSession) {
        callbacks.remove(session)
    }

    private fun attachSessionMessageDelegate(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender
                ): GeckoResult<Any>? {
                    Log.d(TAG, "Session onMessage受信: $message")
                    val json = message as? JSONObject ?: return null
                    val url = json.optString("url", "")
                    val colorStr = json.opt("themeColor")
                        ?.toString()
                        ?.trim()
                        ?.takeUnless { it.isEmpty() || it == "null" }
                    val color = colorStr?.let { parseColor(it) }
                    mainHandler.post {
                        callbacks[session]?.invoke(color, url)
                    }
                    return null
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        private const val TAG = "ThemeColorExt"
        private const val NATIVE_APP_ID = "themeColorBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/theme_color_bridge/"

        private fun parseColor(colorValue: String): Color? {
            return try {
                Color(colorValue.toColorInt())
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "色のパース失敗: $colorValue")
                null
            }
        }
    }
}

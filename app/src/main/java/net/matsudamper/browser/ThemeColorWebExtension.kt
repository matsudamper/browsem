package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap

/**
 * theme-colorメタタグを取得するためのビルトインWebExtension。
 * コンテンツスクリプトからネイティブメッセージングでtheme-colorの値を受け取る。
 */
internal class ThemeColorWebExtension {
    private var extension: WebExtension? = null
    private val sessionCallbacks = ConcurrentHashMap<GeckoSession, (Color?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    extension = ext
                    ext?.setMessageDelegate(createMessageDelegate(), NATIVE_APP_ID)
                },
                { error ->
                    Log.e(TAG, "theme-color拡張のインストールに失敗", error)
                },
            )
    }

    /**
     * セッションにtheme-colorコールバックを登録する。
     * コンテンツスクリプトからメッセージを受信した際にコールバックが呼ばれる。
     */
    fun registerSession(session: GeckoSession, callback: (Color?) -> Unit) {
        sessionCallbacks[session] = callback
    }

    fun unregisterSession(session: GeckoSession) {
        sessionCallbacks.remove(session)
    }

    private fun createMessageDelegate(): WebExtension.MessageDelegate {
        return object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender,
            ) = null.also {
                val session = sender.session ?: return@also
                val callback = sessionCallbacks[session] ?: return@also
                val json = message as? JSONObject ?: return@also
                val themeColorStr = json.optString("themeColor", "")
                    .takeIf { it.isNotEmpty() }
                val color = themeColorStr?.let { parseColor(it) }
                mainHandler.post { callback(color) }
            }
        }
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
                null
            }
        }
    }
}

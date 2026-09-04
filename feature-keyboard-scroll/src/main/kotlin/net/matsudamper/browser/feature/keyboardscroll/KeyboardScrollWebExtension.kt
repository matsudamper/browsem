package net.matsudamper.browser.feature.keyboardscroll

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * キーボード表示時にフォーカス中の入力欄を可視範囲へスクロールさせる拡張。
 *
 * スクロール自体はコンテンツスクリプトで完結する。ネイティブ側は
 * visual viewport の実測値を受け取ってログに出すだけで、キーボード高さが
 * Gecko へ届いているかを調べるための診断に使う。
 */
class KeyboardScrollWebExtension {
    private var extension: WebExtension? = null
    private val sessions = CopyOnWriteArrayList<GeckoSession>()

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    if (ext == null) return@accept
                    extension = ext
                    sessions.forEach { session -> attachMessageDelegate(session, ext) }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    /**
     * 診断ログを受け取るためにセッションを登録する。
     */
    fun registerSession(session: GeckoSession) {
        sessions.addIfAbsent(session)
        extension?.also { ext -> attachMessageDelegate(session, ext) }
    }

    fun unregisterSession(session: GeckoSession) {
        sessions.remove(session)
    }

    private fun attachMessageDelegate(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val json = message as? JSONObject ?: return null
                    Log.i(TAG, "viewport $json")
                    return null
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        const val TAG = "KeyboardScrollExt"
        private const val NATIVE_APP_ID = "keyboardScrollBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/keyboard_scroll_bridge/"
    }
}

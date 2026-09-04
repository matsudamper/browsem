package net.matsudamper.browser.feature.keyboardscroll

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * キーボード表示中にページ側へスクロール余地を作らせる拡張。
 *
 * Gecko は onKeyboardHeight を受け取っても visual viewport を縮めないため、
 * 文書末尾にある入力欄はスクロール上限に阻まれてキーボードの上まで来られない
 * (Issue #674)。GeckoView を物理的に縮める方法は Gecko がポップアップを
 * 閉じてしまうため使えない。
 *
 * そこでキーボードの高さをページへ渡し、表示中だけ文書の下端に余白を足して
 * スクロールできるようにする。リサイズを伴わないのでポップアップは閉じない。
 */
class KeyboardScrollWebExtension {
    private var extension: WebExtension? = null

    // all_frames のため iframe ごとにポートが張られる。全てへ配信する。
    private val ports = ConcurrentHashMap<GeckoSession, MutableList<WebExtension.Port>>()
    private val sessions = ConcurrentHashMap<GeckoSession, Int>()

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    if (ext == null) return@accept
                    extension = ext
                    sessions.keys.forEach { session -> attachPortDelegate(session, ext) }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    fun registerSession(session: GeckoSession) {
        sessions[session] = 0
        extension?.also { ext -> attachPortDelegate(session, ext) }
    }

    /**
     * セッションの登録を解除する。
     *
     * ページ側にキーボード余白が残らないよう 0 を送ってからポートを捨てる。
     * content script は再接続しないため、ポートを黙って捨てると余白が残る。
     */
    fun unregisterSession(session: GeckoSession) {
        postKeyboardHeight(session, 0)
        sessions.remove(session)
        // ポートは捨てない。content script は connectNative を一度しか呼ばないため、
        // ここで参照を消すとタブへ戻ったときに高さを届けられなくなる。
        // ポートは document が破棄されるときの onDisconnect で外れる。
    }

    /**
     * キーボードの高さ (物理ピクセル) をページへ通知する。
     *
     * 同じ値の連投はしない。ページ側は devicePixelRatio で CSS ピクセルへ直す。
     */
    fun setKeyboardHeight(session: GeckoSession, heightPx: Int) {
        if (sessions[session] == heightPx) return
        sessions[session] = heightPx
        postKeyboardHeight(session, heightPx)
    }

    private fun postKeyboardHeight(session: GeckoSession, heightPx: Int) {
        val message = JSONObject().put("keyboardHeightPx", heightPx)
        ports[session]?.forEach { port ->
            runCatching { port.postMessage(message) }
        }
    }

    private fun attachPortDelegate(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    ports.getOrPut(session) { CopyOnWriteArrayList() }.add(port)
                    port.setDelegate(
                        object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) = Unit

                            override fun onDisconnect(port: WebExtension.Port) {
                                val remaining = ports[session] ?: return
                                remaining.remove(port)
                                if (remaining.isEmpty()) ports.remove(session)
                            }
                        },
                    )
                    // 接続前に確定した高さを取りこぼさないよう、現在値を送る。
                    val height = sessions[session] ?: return
                    port.postMessage(JSONObject().put("keyboardHeightPx", height))
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        private const val TAG = "KeyboardScrollExt"
        private const val NATIVE_APP_ID = "keyboardScrollBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/keyboard_scroll_bridge/"
    }
}

package net.matsudamper.browser.feature.keyboardscroll

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime

/**
 * キーボード表示時にフォーカス中の入力欄を可視範囲へスクロールさせる拡張。
 *
 * ネイティブ側とのやり取りは無く、コンテンツスクリプトだけで完結するため
 * セッションごとの登録は不要。
 */
class KeyboardScrollWebExtension {
    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    companion object {
        private const val TAG = "KeyboardScrollExt"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/keyboard_scroll_bridge/"
    }
}

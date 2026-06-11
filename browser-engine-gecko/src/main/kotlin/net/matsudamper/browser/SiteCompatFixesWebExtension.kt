package net.matsudamper.browser

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime

/**
 * Gecko の既知バグでサイト側の機能が壊れているケースをアプリ側で補正する
 * コンテンツスクリプト群をインストールする (Firefox の webcompat interventions 相当)。
 *
 * 現在の修正対象:
 * - X (x.com / twitter.com) 画像ビューアーのピンチズーム (Bugzilla 2007555)
 *
 * セッションごとの登録やネイティブメッセージングは不要なため、
 * GeckoRuntime 生成時に一度インストールするだけでよい。
 */
class SiteCompatFixesWebExtension {
    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                {},
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    companion object {
        private const val TAG = "SiteCompatFixesExt"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/site_compat_fixes/"
    }
}

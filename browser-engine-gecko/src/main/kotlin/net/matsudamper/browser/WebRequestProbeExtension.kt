package net.matsudamper.browser

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime

/**
 * 診断用ビルトイン拡張機能。
 * webRequest.onBeforeRequest が拡張機能内で実際に発火するかを確認するため、
 * <all_urls> に対してリスナーを登録し、何もブロックせずに console.log するだけの最小拡張。
 * AdGuard が機能しない原因切り分けに使う。
 */
class WebRequestProbeExtension {
    fun install(runtime: GeckoRuntime) {
        Log.i(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.i(
                        TAG,
                        "WebRequestProbe インストール完了 id=${ext?.id} " +
                            "version=${ext?.metaData?.version} " +
                            "requiredPermissions=${ext?.metaData?.requiredPermissions?.toList()} " +
                            "requiredOrigins=${ext?.metaData?.requiredOrigins?.toList()}",
                    )
                },
                { error ->
                    Log.w(TAG, "WebRequestProbe インストール失敗", error)
                },
            )
    }

    companion object {
        private const val TAG = "AdGuardDiag"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/web_request_probe/"
    }
}

package net.matsudamper.browser.feature.webauthncompat

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime

class WebAuthnCompatWebExtension {
    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                {},
                { error -> Log.e(TAG, "インストール失敗", error) },
            )
    }

    companion object {
        private const val TAG = "WebAuthnCompatExt"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/webauthn_compat/"
    }
}

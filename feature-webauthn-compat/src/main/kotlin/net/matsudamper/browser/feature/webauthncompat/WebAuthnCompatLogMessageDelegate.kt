package net.matsudamper.browser.feature.webauthncompat

import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

object WebAuthnCompatLogMessageDelegate : WebExtension.MessageDelegate {
    const val NATIVE_APP = "browsem_webauthn_compat_log"
    private const val TAG = "WebAuthnCompat"

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender,
    ): GeckoResult<Any>? {
        Log.d(TAG, message.toString())
        return null
    }
}

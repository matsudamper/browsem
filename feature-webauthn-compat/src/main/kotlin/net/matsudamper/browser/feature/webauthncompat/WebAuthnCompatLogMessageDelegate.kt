package net.matsudamper.browser.feature.webauthncompat

import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

class WebAuthnCompatLogMessageDelegate(
    private val onLog: (String) -> Unit,
) : WebExtension.MessageDelegate {
    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender,
    ): GeckoResult<Any>? {
        val text = message.toString()
        Log.d(TAG, text)
        onLog(text)
        return null
    }

    companion object {
        const val NATIVE_APP = "browsem_webauthn_compat_log"
        const val TAG = "WebAuthnCompat"
    }
}

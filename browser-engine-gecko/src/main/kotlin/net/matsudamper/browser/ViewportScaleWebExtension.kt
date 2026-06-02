package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ConcurrentHashMap

class ViewportScaleWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<GeckoSession, (Float) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
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

    fun registerSession(session: GeckoSession, callback: (Float) -> Unit) {
        callbacks[session] = callback
        extension?.also { ext ->
            attachSessionMessageDelegate(session, ext)
        }
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
                    val json = message as? JSONObject ?: return null
                    val scale = json.optDouble("scale", 1.0).toFloat()
                    mainHandler.post {
                        callbacks[session]?.invoke(scale)
                    }
                    return null
                }
            },
            NATIVE_APP_ID,
        )
    }

    companion object {
        private const val TAG = "ViewportScaleExt"
        private const val NATIVE_APP_ID = "viewportScaleBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/viewport_scale_bridge/"
    }
}

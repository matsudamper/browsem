package net.matsudamper.browser

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.WeakHashMap

internal object ThemeColorWebExtension {
    private const val EXTENSION_ID = "theme-color@browsem"
    private const val EXTENSION_LOCATION = "resource://android/assets/theme_color_extension/"
    private const val NATIVE_APP = "theme-color"

    private val listeners = WeakHashMap<GeckoSession, (Color?) -> Unit>()

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController.installBuiltIn(EXTENSION_LOCATION).accept(
            { extension ->
                runtime.webExtensionController.setMessageDelegate(
                    extension,
                    messageDelegate,
                    NATIVE_APP,
                )
            },
            {
                runtime.webExtensionController.list().accept({ installedExtensions ->
                    val installed = installedExtensions.firstOrNull { it.id == EXTENSION_ID } ?: return@accept
                    runtime.webExtensionController.setMessageDelegate(
                        installed,
                        messageDelegate,
                        NATIVE_APP,
                    )
                }, {})
            },
        )
    }

    fun observe(session: GeckoSession, onThemeColor: (Color?) -> Unit) {
        listeners[session] = onThemeColor
    }

    fun removeObserver(session: GeckoSession) {
        listeners.remove(session)
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP) return null

            val payload = when (message) {
                is JSONObject -> message
                is String -> runCatching { JSONObject(message) }.getOrNull()
                else -> null
            } ?: return null

            val colorText = payload.optString("color", "").takeIf { it.isNotBlank() }
            val color = colorText?.let {
                runCatching { Color(it.toColorInt()) }.getOrNull()
            }

            listeners[sender.session]?.invoke(color)
            return null
        }
    }
}

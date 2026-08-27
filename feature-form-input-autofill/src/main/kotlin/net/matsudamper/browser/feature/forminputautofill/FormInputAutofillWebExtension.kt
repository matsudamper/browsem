package net.matsudamper.browser.feature.forminputautofill

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ページ固有フォーム入力の保存・候補表示用 WebExtension。
 * フォーカス中フレームのポートへだけ fill を送る。
 */
class FormInputAutofillWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionPorts = ConcurrentHashMap<GeckoSession, MutableSet<WebExtension.Port>>()
    private val lastFocusPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val delegatedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    var onFieldFocus: ((String, String) -> Unit)? = null

    @Volatile
    var onFieldBlur: (() -> Unit)? = null

    @Volatile
    var onFormSubmit: ((String, List<FormInputFieldMessage>) -> Unit)? = null

    @Volatile
    var onFocusPortDisconnected: (() -> Unit)? = null

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    if (ext == null) return@accept
                    extension = ext
                    attachedSessions.forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error -> Log.e(TAG, "インストール失敗", error) },
            )
    }

    fun registerSession(session: GeckoSession) {
        attachedSessions.add(session)
        extension?.also { ext -> attachSessionDelegate(session, ext) }
    }

    fun unregisterSession(session: GeckoSession) {
        attachedSessions.remove(session)
        delegatedSessions.remove(session)
        sessionPorts.remove(session)
        lastFocusPorts.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    fun fill(session: GeckoSession, fieldKey: String, value: String) {
        val port = lastFocusPorts[session] ?: return
        val message = JSONObject().apply {
            put("action", "fill")
            put("fieldKey", fieldKey)
            put("value", value)
        }
        runCatching { port.postMessage(message) }
            .onFailure { error -> Log.w(TAG, "fill 送信に失敗", error) }
    }

    private fun attachSessionDelegate(session: GeckoSession, ext: WebExtension) {
        if (!attachedSessions.contains(session)) return
        if (!delegatedSessions.add(session)) return
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? = null

                override fun onConnect(port: WebExtension.Port) {
                    sessionPorts
                        .getOrPut(session) { Collections.newSetFromMap(ConcurrentHashMap()) }
                        .add(port)
                    val connectedPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            when (json.optString("action")) {
                                "field-focus" -> {
                                    val fieldKey = json.optString("fieldKey")
                                    val pageUrl = json.optString("pageUrl")
                                    lastFocusPorts[session] = port
                                    mainHandler.post {
                                        onFieldFocus?.invoke(fieldKey, pageUrl)
                                    }
                                }
                                "field-blur" -> {
                                    mainHandler.post { onFieldBlur?.invoke() }
                                }
                                "form-submit" -> {
                                    val pageUrl = json.optString("pageUrl")
                                    val fields = parseFields(json.optJSONArray("fields"))
                                    mainHandler.post {
                                        onFormSubmit?.invoke(pageUrl, fields)
                                    }
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            sessionPorts[session]?.remove(connectedPort)
                            if (lastFocusPorts[session] === connectedPort) {
                                lastFocusPorts.remove(session)
                                mainHandler.post { onFocusPortDisconnected?.invoke() }
                            }
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    private fun parseFields(array: JSONArray?): List<FormInputFieldMessage> {
        if (array == null) return emptyList()
        val result = mutableListOf<FormInputFieldMessage>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val fieldKey = item.optString("fieldKey")
            val value = item.optString("value")
            if (fieldKey.isBlank()) continue
            result.add(FormInputFieldMessage(fieldKey = fieldKey, value = value))
        }
        return result
    }

    companion object {
        private const val TAG = "FormInputAutofillExt"
        private const val NATIVE_APP_ID = "formInputAutofillBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/form_input_autofill_bridge/"
    }
}

data class FormInputFieldMessage(
    val fieldKey: String,
    val value: String,
)

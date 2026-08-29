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
    private val sessionListeners = ConcurrentHashMap<GeckoSession, SessionListener>()
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val delegatedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    interface SessionListener {
        fun onFieldFocus(fieldKey: String, pageUrl: String)
        fun onFieldBlur()
        fun onFormSubmit(pageUrl: String, fields: List<FormInputFieldMessage>)
        fun onFocusPortDisconnected()
    }

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

    fun registerSession(session: GeckoSession, listener: SessionListener) {
        sessionListeners[session] = listener
        attachedSessions.add(session)
        extension?.also { ext -> attachSessionDelegate(session, ext) }
    }

    fun unregisterSession(session: GeckoSession) {
        sessionListeners.remove(session)
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
                            val listener = sessionListeners[session] ?: return
                            when (json.optString("action")) {
                                "field-focus" -> {
                                    val fieldKey = json.optString("fieldKey")
                                    val pageUrl = json.optString("pageUrl")
                                    lastFocusPorts[session] = port
                                    mainHandler.post {
                                        listener.onFieldFocus(fieldKey, pageUrl)
                                    }
                                }
                                "field-blur" -> {
                                    mainHandler.post { listener.onFieldBlur() }
                                }
                                "form-submit" -> {
                                    val pageUrl = json.optString("pageUrl")
                                    val fields = parseFields(json.optJSONArray("fields"))
                                    mainHandler.post {
                                        listener.onFormSubmit(pageUrl, fields)
                                    }
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            sessionPorts[session]?.remove(connectedPort)
                            if (lastFocusPorts[session] === connectedPort) {
                                lastFocusPorts.remove(session)
                                val listener = sessionListeners[session]
                                if (listener != null) {
                                    mainHandler.post { listener.onFocusPortDisconnected() }
                                }
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

    internal fun dispatchFieldFocus(session: GeckoSession, fieldKey: String, pageUrl: String) {
        sessionListeners[session]?.onFieldFocus(fieldKey, pageUrl)
    }

    internal fun dispatchFieldBlur(session: GeckoSession) {
        sessionListeners[session]?.onFieldBlur()
    }

    internal fun dispatchFormSubmit(
        session: GeckoSession,
        pageUrl: String,
        fields: List<FormInputFieldMessage>,
    ) {
        sessionListeners[session]?.onFormSubmit(pageUrl, fields)
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

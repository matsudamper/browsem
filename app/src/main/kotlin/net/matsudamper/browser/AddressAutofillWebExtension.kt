package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 住所欄へ値を入れるビルトイン WebExtension。
 * Gecko FormAutofill と Autofill.Session は shadow DOM 内の cross-origin iframe
 * を埋められないため、all_frames のコンテンツスクリプトへ fill メッセージを送る。
 */
internal class AddressAutofillWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionPorts = ConcurrentHashMap<GeckoSession, MutableSet<WebExtension.Port>>()
    private val pendingFills = ConcurrentHashMap<GeckoSession, PendingFill>()
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val delegatedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    var onFieldFocus: ((String) -> Unit)? = null

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id}")
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
        pendingFills.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    fun fill(
        session: GeckoSession,
        address: Autocomplete.Address,
        mode: AddressAutofillFillMode,
    ) {
        val message = address.toFillMessage(mode)
        pendingFills[session] = PendingFill(
            message = message,
            untilElapsedRealtime = SystemClock.elapsedRealtime() + FILL_RETRY_WINDOW_MS,
        )
        postFill(session, message)
        mainHandler.postDelayed({ postFill(session, message) }, 250)
        mainHandler.postDelayed({ postFill(session, message) }, 1_000)
    }

    private fun postFill(session: GeckoSession, message: JSONObject) {
        val ports = sessionPorts[session].orEmpty()
        Log.i(TAG, "fill ports=${ports.size}")
        if (ports.isEmpty()) {
            Log.w(TAG, "fill: ポート未接続")
            return
        }
        ports.forEach { port ->
            runCatching { port.postMessage(message) }
                .onFailure { error -> Log.w(TAG, "fill 送信に失敗", error) }
        }
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
                    Log.d(TAG, "onConnect: ${port.sender.url}")
                    sessionPorts
                        .getOrPut(session) { Collections.newSetFromMap(ConcurrentHashMap()) }
                        .add(port)
                    val connectedPort = port
                    val pending = pendingFills[session]
                    if (pending != null && SystemClock.elapsedRealtime() < pending.untilElapsedRealtime) {
                        runCatching { port.postMessage(pending.message) }
                            .onFailure { error -> Log.w(TAG, "pending fill 送信に失敗", error) }
                    }
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            if (json.optString("action") != "field-focus") return
                            val kind = json.optString("kind")
                            mainHandler.post { onFieldFocus?.invoke(kind) }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            sessionPorts[session]?.remove(connectedPort)
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    private class PendingFill(
        val message: JSONObject,
        val untilElapsedRealtime: Long,
    )

    companion object {
        private const val TAG = "AddressAutofillExt"
        private const val NATIVE_APP_ID = "addressAutofillBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/address_autofill_bridge/"
        private const val FILL_RETRY_WINDOW_MS = 5_000L
    }
}

internal fun Autocomplete.Address.toFillMessage(mode: AddressAutofillFillMode): JSONObject {
    val address = this
    val modeValue = when (mode) {
        AddressAutofillFillMode.Email -> "email"
        AddressAutofillFillMode.Address -> "address"
    }
    return JSONObject().apply {
        put("action", "fill")
        put("mode", modeValue)
        put(
            "address",
            JSONObject().apply {
                put("name", address.name.orEmpty())
                put("givenName", address.givenName.orEmpty())
                put("additionalName", address.additionalName.orEmpty())
                put("familyName", address.familyName.orEmpty())
                put("organization", address.organization.orEmpty())
                put("streetAddress", address.streetAddress.orEmpty())
                put("addressLevel1", address.addressLevel1.orEmpty())
                put("addressLevel2", address.addressLevel2.orEmpty())
                put("addressLevel3", address.addressLevel3.orEmpty())
                put("postalCode", address.postalCode.orEmpty())
                put("country", address.country.orEmpty())
                put("tel", address.tel.orEmpty())
                put(
                    "email",
                    if (mode == AddressAutofillFillMode.Email) {
                        address.email.orEmpty()
                    } else {
                        ""
                    },
                )
            },
        )
    }
}

package net.matsudamper.browser.feature.addressautofill

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 住所欄へ値を入れるビルトイン WebExtension。
 * Gecko FormAutofill と Autofill.Session は shadow DOM 内の cross-origin iframe
 * を埋められないため、all_frames のコンテンツスクリプトへ fill メッセージを送る。
 *
 * fill は field-focus を送ったポート（フォーカス中フレーム）にだけ送る。
 * 別 iframe や遷移後の新規ドキュメントへ個人情報を配らない。
 */
class AddressAutofillWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionPorts = ConcurrentHashMap<GeckoSession, MutableSet<WebExtension.Port>>()
    private val lastFocusPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()
    private val pendingFills = ConcurrentHashMap<GeckoSession, PendingFill>()
    private val fillRetryRunnables = ConcurrentHashMap<GeckoSession, MutableList<Runnable>>()
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val delegatedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    var onFieldFocus: ((String) -> Unit)? = null

    @Volatile
    var onFieldBlur: (() -> Unit)? = null

    @Volatile
    var onFocusPortDisconnected: (() -> Unit)? = null

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
        lastFocusPorts.remove(session)
        pendingFills.remove(session)
        fillRetryRunnables.remove(session)?.forEach { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
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
        val target = lastFocusPorts[session]
        pendingFills[session] = PendingFill(
            message = message,
            untilElapsedRealtime = SystemClock.elapsedRealtime() + FILL_RETRY_WINDOW_MS,
            targetUrl = target?.sender?.url,
        )
        fillRetryRunnables.remove(session)?.forEach { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        if (target == null) {
            Log.w(TAG, "fill: フォーカス中のポートがない")
            return
        }
        postFill(target, message)
        val retries = mutableListOf<Runnable>()
        listOf(250L, 1_000L).forEach { delayMs ->
            val retry = Runnable {
                val current = lastFocusPorts[session]
                if (current === target) {
                    postFill(target, message)
                }
            }
            retries.add(retry)
            mainHandler.postDelayed(retry, delayMs)
        }
        fillRetryRunnables[session] = retries
    }

    private fun postFill(port: WebExtension.Port, message: JSONObject) {
        Log.i(TAG, "fill url=${port.sender.url}")
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
                    Log.d(TAG, "onConnect: ${port.sender.url}")
                    sessionPorts
                        .getOrPut(session) { Collections.newSetFromMap(ConcurrentHashMap()) }
                        .add(port)
                    val connectedPort = port
                    val pending = pendingFills[session]
                    val senderUrl = port.sender.url
                    if (
                        pending != null &&
                        SystemClock.elapsedRealtime() < pending.untilElapsedRealtime &&
                        pending.targetUrl != null &&
                        senderUrl == pending.targetUrl
                    ) {
                        lastFocusPorts[session] = port
                        runCatching { port.postMessage(pending.message) }
                            .onFailure { error -> Log.w(TAG, "pending fill 送信に失敗", error) }
                    }
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            when (json.optString("action")) {
                                "field-focus" -> {
                                    val kind = json.optString("kind")
                                    lastFocusPorts[session] = port
                                    mainHandler.post { onFieldFocus?.invoke(kind) }
                                }
                                "field-blur" -> {
                                    mainHandler.post { onFieldBlur?.invoke() }
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

    private class PendingFill(
        val message: JSONObject,
        val untilElapsedRealtime: Long,
        val targetUrl: String?,
    )

    companion object {
        private const val TAG = "AddressAutofillExt"
        private const val NATIVE_APP_ID = "addressAutofillBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/address_autofill_bridge/"
        private const val FILL_RETRY_WINDOW_MS = 5_000L
    }
}

fun Autocomplete.Address.toFillMessage(mode: AddressAutofillFillMode): JSONObject {
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

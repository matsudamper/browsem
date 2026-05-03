package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * navigator.geolocation をモック位置情報で上書きするビルトイン WebExtension。
 * コンテンツスクリプトが connectNative でポートを確立し、
 * ネイティブ側から現在の設定を返す。設定が更新された場合は update メッセージを送信する。
 */
class MockLocationWebExtension {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 現在のモック位置情報設定
    @Volatile private var currentConfig: MockLocationConfig = MockLocationConfig(
        enabled = false,
        latitude = DEFAULT_LATITUDE,
        longitude = DEFAULT_LONGITUDE,
    )

    // セッションごとの接続ポート
    private val sessionPorts = ConcurrentHashMap<GeckoSession, WebExtension.Port>()

    // デリゲートを設定済みのセッションを追跡（二重設定を防ぐ）
    private val attachedSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    attachedSessions.forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    fun registerSession(session: GeckoSession) {
        attachedSessions.add(session)
        extension?.also { ext ->
            attachSessionDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        attachedSessions.remove(session)
        sessionPorts.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /** モック位置情報設定を更新し、接続済みの全セッションに通知する */
    fun updateConfig(config: MockLocationConfig) {
        currentConfig = config
        val message = config.toJson().apply { put("action", "update") }
        sessionPorts.values.forEach { port ->
            try {
                port.postMessage(message)
            } catch (e: Exception) {
                Log.w(TAG, "updateConfig: ポートへの送信に失敗", e)
            }
        }
    }

    private fun attachSessionDelegate(session: GeckoSession, ext: WebExtension) {
        if (!attachedSessions.contains(session)) return
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? = null

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "onConnect: ポート接続")
                    sessionPorts[session] = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            if (json.optString("action") == "getConfig") {
                                val response = currentConfig.toJson().apply { put("action", "config") }
                                try {
                                    port.postMessage(response)
                                } catch (e: Exception) {
                                    Log.w(TAG, "getConfig 応答に失敗", e)
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            sessionPorts.remove(session)
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    data class MockLocationConfig(
        val enabled: Boolean,
        val latitude: Double,
        val longitude: Double,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("enabled", enabled)
            put("latitude", latitude)
            put("longitude", longitude)
        }
    }

    companion object {
        private const val TAG = "MockLocationExt"
        private const val NATIVE_APP_ID = "mockLocationBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/mock_location_bridge/"

        const val DEFAULT_LATITUDE = 35.685175
        const val DEFAULT_LONGITUDE = 139.752797
    }
}

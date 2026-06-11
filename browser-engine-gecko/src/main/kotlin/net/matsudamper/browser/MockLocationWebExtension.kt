package net.matsudamper.browser

import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * navigator.geolocation をサイトごとの設定に応じて差し替えるビルトイン WebExtension。
 * コンテンツスクリプトが connectNative でポートを確立し、
 * ネイティブ側から接続元ホストに応じたモード（mock/deny/real）を返す。
 * 設定が更新された場合は update メッセージを送信する。
 */
class MockLocationWebExtension {
    private var extension: WebExtension? = null

    // 現在の位置情報設定
    @Volatile private var currentConfig: GeolocationConfig = GeolocationConfig(
        latitude = DEFAULT_LATITUDE,
        longitude = DEFAULT_LONGITUDE,
        siteModes = emptyMap(),
    )

    /** ページが位置情報を要求した際にホスト名を通知するコールバック */
    @Volatile var onGeolocationRequested: ((host: String) -> Unit)? = null

    // セッションごとの接続ポート。iframe を含む各フレームから個別に接続されるため複数保持する
    private val sessionPorts = ConcurrentHashMap<GeckoSession, MutableSet<WebExtension.Port>>()

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

    /** 位置情報設定を更新し、接続済みの全セッションへ各ホストに応じたモードを通知する */
    fun updateConfig(config: GeolocationConfig) {
        currentConfig = config
        sessionPorts.values.flatten().forEach { port ->
            val message = buildConfigMessage(portHost(port), action = "update")
            try {
                port.postMessage(message)
            } catch (e: Exception) {
                Log.w(TAG, "updateConfig: ポートへの送信に失敗", e)
            }
        }
    }

    /** 接続元ページの URL からホスト名を取り出す */
    private fun portHost(port: WebExtension.Port): String? {
        return runCatching { URI(port.sender.url) }.getOrNull()?.host
    }

    /** ホストに応じた設定メッセージを構築する */
    private fun buildConfigMessage(host: String?, action: String): JSONObject {
        val config = currentConfig
        return JSONObject().apply {
            put("action", action)
            put("mode", config.resolveMode(host).jsonValue)
            put("latitude", config.latitude)
            put("longitude", config.longitude)
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
                    sessionPorts
                        .getOrPut(session) { Collections.newSetFromMap(ConcurrentHashMap()) }
                        .add(port)
                    val connectedPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            when (json.optString("action")) {
                                "getConfig" -> {
                                    val response = buildConfigMessage(portHost(port), action = "config")
                                    try {
                                        port.postMessage(response)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "getConfig 応答に失敗", e)
                                    }
                                }
                                // ページが位置情報を要求したことの通知。
                                // 「サイトの設定」画面に位置情報の項目を表示するために記録する
                                "geolocationRequested" -> {
                                    val host = portHost(port) ?: return
                                    onGeolocationRequested?.invoke(host)
                                }
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "onDisconnect: ポート切断")
                            sessionPorts[session]?.remove(connectedPort)
                        }
                    })
                }
            },
            NATIVE_APP_ID,
        )
    }

    /** サイトごとの位置情報の扱い */
    enum class GeolocationMode(val jsonValue: String) {
        // モック座標を返す
        MOCK("mock"),
        // 位置情報の取得を拒否する
        DENY("deny"),

        // 実際の位置情報を返す（Gecko 本体の geolocation へ委譲）
        REAL("real"),
    }

    /**
     * 位置情報設定全体。
     * サイトごとの設定が無いホストにはモック座標を返す（デフォルト）。
     */
    data class GeolocationConfig(
        val latitude: Double,
        val longitude: Double,
        val siteModes: Map<String, GeolocationMode>,
    ) {
        fun resolveMode(host: String?): GeolocationMode {
            return siteModes[host] ?: GeolocationMode.MOCK
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

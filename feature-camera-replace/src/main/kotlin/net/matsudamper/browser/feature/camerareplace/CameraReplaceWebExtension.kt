package net.matsudamper.browser.feature.camerareplace

import android.util.Log
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * getUserMedia のカメラ要求をサイトごとの設定に応じて画像へ置き換えるビルトイン WebExtension。
 * コンテンツスクリプトがカメラを要求されるたびにネイティブへ問い合わせ、
 * セッションごとに登録された [CameraDecisionResolver] が扱いを決める。
 */
class CameraReplaceWebExtension {
    private var extension: WebExtension? = null

    private val sessionRegistrations = ConcurrentHashMap<GeckoSession, SessionRegistration>()

    fun install(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    sessionRegistrations.keys.forEach { session ->
                        attachSessionDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    /**
     * @param topUrlProvider iframe からの要求をトップレベルサイトの設定で制御するための、表示中ページの URL
     */
    fun registerSession(
        session: GeckoSession,
        topUrlProvider: () -> String?,
        resolver: CameraDecisionResolver,
    ) {
        sessionRegistrations[session] = SessionRegistration(
            topUrlProvider = topUrlProvider,
            resolver = resolver,
        )
        val extension = extension ?: return
        attachSessionDelegate(session, extension)
    }

    fun unregisterSession(session: GeckoSession) {
        sessionRegistrations.remove(session)
        extension?.let { ext ->
            session.webExtensionController.setMessageDelegate(ext, null, NATIVE_APP_ID)
        }
    }

    /**
     * 要求元に適用するホストを返す。
     * 標準ブラウザのカメラ許可と同様にトップレベルサイト基準で制御するため、
     * iframe からの要求にはトップレベルページのホストを使う。
     */
    private fun resolveHost(session: GeckoSession, sender: WebExtension.MessageSender): String? {
        val senderHost = runCatching { URI(sender.url) }.getOrNull()?.host
        if (sender.isTopLevel) return senderHost
        val topUrl = sessionRegistrations[session]?.topUrlProvider?.invoke()
        val topHost = topUrl?.let { runCatching { URI(it) }.getOrNull()?.host }
        return topHost ?: senderHost
    }

    private fun isCrossOriginSubframe(session: GeckoSession, sender: WebExtension.MessageSender): Boolean {
        if (sender.isTopLevel) return false
        val senderOrigin = originOf(sender.url)
        val topOrigin = sessionRegistrations[session]?.topUrlProvider?.invoke()?.let(::originOf)
        return senderOrigin == null || senderOrigin != topOrigin
    }

    private fun originOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return "$scheme://$host:${uri.port}"
    }

    private fun attachSessionDelegate(session: GeckoSession, ext: WebExtension) {
        if (!sessionRegistrations.containsKey(session)) return
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val json = message as? JSONObject ?: return null
                    if (json.optString("action") != "resolveCamera") return null
                    val registration = sessionRegistrations[session] ?: return null
                    if (isCrossOriginSubframe(session, sender)) {
                        // 画像を返すとカメラ利用を委譲されていない iframe にも画像が渡ってしまう。
                        // 委譲の確認は Gecko 本体の getUserMedia が行うため、そちらへ任せる
                        return GeckoResult.fromValue(CameraDecision.Real.toJson())
                    }
                    val host = resolveHost(session, sender)
                    if (host == null) {
                        return GeckoResult.fromValue(CameraDecision.Deny.toJson())
                    }
                    val result = GeckoResult<Any>()
                    registration.resolver.resolve(host) { decision ->
                        result.complete(decision.toJson())
                    }
                    return result
                }
            },
            NATIVE_APP_ID,
        )
    }

    private class SessionRegistration(
        val topUrlProvider: () -> String?,
        val resolver: CameraDecisionResolver,
    )

    fun interface CameraDecisionResolver {
        /** [onResult] は必ず一度呼ぶ。呼ばないとページの getUserMedia が完了しない */
        fun resolve(host: String, onResult: (CameraDecision) -> Unit)
    }

    sealed interface CameraDecision {
        /** 実際のカメラを使う。可否は Gecko 本体の権限処理で改めて判断される */
        data object Real : CameraDecision

        /**
         * カメラ映像の代わりに画像を返す
         * @param imageDataUrl 返す画像。null の場合はページ側で代替の画像を描く
         */
        data class Image(val imageDataUrl: String?) : CameraDecision

        data object Deny : CameraDecision
    }

    private fun CameraDecision.toJson(): JSONObject {
        return JSONObject().apply {
            when (val decision = this@toJson) {
                CameraDecision.Real -> put("mode", "real")

                is CameraDecision.Image -> {
                    put("mode", "image")
                    put("image", decision.imageDataUrl ?: JSONObject.NULL)
                }

                CameraDecision.Deny -> put("mode", "deny")
            }
        }
    }

    private companion object {
        const val TAG = "CameraReplaceExt"
        const val NATIVE_APP_ID = "cameraReplaceBridge"
        const val EXTENSION_URI = "resource://android/assets/web_extensions/camera_replace_bridge/"
    }
}

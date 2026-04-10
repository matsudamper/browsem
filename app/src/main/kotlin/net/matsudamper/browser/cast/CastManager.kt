package net.matsudamper.browser.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.matsudamper.browser.CastWebExtension
import net.matsudamper.browser.media.MediaSessionBridge
import java.util.concurrent.Executors

/**
 * Chromecastへのキャストセッションを管理するクラス。
 * Cast SDKのCastContext/SessionManagerをラップし、
 * MediaSessionBridgeと連携してローカル/リモート再生を切り替える。
 * Google Play Services未対応端末では機能を無効化する。
 */
class CastManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _castState = MutableStateFlow(CastUiState())
    val castState: StateFlow<CastUiState> = _castState.asStateFlow()

    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var castExecutor: java.util.concurrent.ExecutorService? = null

    // ウェブページからのセッションリクエスト用コールバック
    private var webSessionCallback: ((Boolean, String, String) -> Unit)? = null
    // ウェブページ経由でセッションが開始されたかどうか
    private var isWebInitiatedSession = false

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            Log.d(TAG, "onSessionStarting")
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d(TAG, "onSessionStarted: sessionId=$sessionId device=${session.castDevice?.friendlyName}")
            val deviceName = session.castDevice?.friendlyName ?: ""
            _castState.value = _castState.value.copy(isConnected = true, deviceName = deviceName)

            // ウェブページからのリクエストの場合はコールバックで通知
            val webCallback = webSessionCallback
            if (webCallback != null) {
                webSessionCallback = null
                isWebInitiatedSession = true
                MediaSessionBridge.pause()
                MediaSessionBridge.updateCasting(true)
                webCallback(true, sessionId, deviceName)
                return
            }

            // ツールバーからのキャスト: ローカル再生を一時停止してキャストに切り替え
            val state = MediaSessionBridge.playbackState.value
            MediaSessionBridge.pause()
            MediaSessionBridge.updateCasting(true)
            // メディアURLが有効であればChromecastにロード
            val url = state.mediaSourceUrl
            if (isCastableUrl(url)) {
                loadMediaOnCast(session, url, state.title, state.positionMs)
            } else {
                Log.w(TAG, "メディアURLがキャスト不可: url=${url.take(100)}")
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e(TAG, "onSessionStartFailed: error=$error")
            _castState.value = _castState.value.copy(isConnected = false, deviceName = "")
            MediaSessionBridge.updateCasting(false)
            // ウェブページからのリクエスト失敗を通知
            webSessionCallback?.invoke(false, "", "")
            webSessionCallback = null
        }

        override fun onSessionEnding(session: CastSession) {
            Log.d(TAG, "onSessionEnding")
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d(TAG, "onSessionEnded: error=$error")
            _castState.value = _castState.value.copy(isConnected = false, deviceName = "")
            val wasWebInitiated = isWebInitiatedSession
            isWebInitiatedSession = false

            // セッション終了リスナーに通知
            sessionEndedListeners.toList().forEach { it() }

            if (wasWebInitiated) {
                // ウェブ経由のセッションはページ側で再生制御するため、ここではキャスト状態のみ解除
                MediaSessionBridge.updateCasting(false)
                return
            }

            // ツールバーからのキャスト終了時、リモート側が再生中だった場合のみローカル再生を再開
            val remoteClient = session.remoteMediaClient
            val wasPlaying = remoteClient?.isPlaying ?: false
            val positionMs = remoteClient?.approximateStreamPosition ?: 0L
            MediaSessionBridge.updateCasting(false)
            if (positionMs > 0) {
                MediaSessionBridge.seekTo(positionMs / 1000.0)
            }
            if (wasPlaying) {
                MediaSessionBridge.play()
            }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            Log.d(TAG, "onSessionResuming")
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.d(TAG, "onSessionResumed: wasSuspended=$wasSuspended")
            val deviceName = session.castDevice?.friendlyName ?: ""
            _castState.value = _castState.value.copy(isConnected = true, deviceName = deviceName)
            MediaSessionBridge.updateCasting(true)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.e(TAG, "onSessionResumeFailed: error=$error")
            _castState.value = _castState.value.copy(isConnected = false, deviceName = "")
            MediaSessionBridge.updateCasting(false)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.d(TAG, "onSessionSuspended: reason=$reason")
        }
    }

    init {
        initializeCastContext()
        // MediaSessionBridgeの状態変化を監視し、キャスト可能URLの有無をUI状態に反映
        MediaSessionBridge.playbackState
            .onEach { state ->
                val hasCastableUrl = isCastableUrl(state.mediaSourceUrl)
                if (_castState.value.hasMediaSourceUrl != hasCastableUrl) {
                    _castState.value = _castState.value.copy(hasMediaSourceUrl = hasCastableUrl)
                }
            }
            .launchIn(scope)
    }

    private fun initializeCastContext() {
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) {
            Log.w(TAG, "Google Play Services が利用不可のためCast機能を無効化: availability=$availability")
            return
        }
        try {
            val executor = Executors.newSingleThreadExecutor()
            castExecutor = executor
            CastContext.getSharedInstance(context, executor).addOnSuccessListener { ctx ->
                castContext = ctx
                sessionManager = ctx.sessionManager
                sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                // CastStateListenerを登録してデバイス検出状態を監視
                ctx.addCastStateListener { castState ->
                    val isAvailable = castState != com.google.android.gms.cast.framework.CastState.NO_DEVICES_AVAILABLE
                    Log.d(TAG, "CastState changed: castState=$castState isAvailable=$isAvailable")
                    _castState.value = _castState.value.copy(isAvailable = isAvailable)
                }
                Log.d(TAG, "CastContext初期化完了")
            }.addOnFailureListener { e ->
                Log.e(TAG, "CastContext初期化失敗", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CastContext初期化例外", e)
        }
    }

    /**
     * キャストセッションを停止する。
     */
    fun stopCasting() {
        Log.d(TAG, "stopCasting")
        sessionManager?.endCurrentSession(true)
    }

    /**
     * キャストデバイス選択ダイアログを表示する。
     * FragmentManagerが不要なDialogを直接インスタンス化する。
     */
    fun showChooserDialog(context: Context) {
        Log.d(TAG, "showChooserDialog")
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
            )
            .build()
        val dialog = MediaRouteChooserDialog(context)
        dialog.routeSelector = selector
        dialog.show()
    }

    /**
     * キャスト中の再生を操作する。
     */
    fun playOnCast() {
        currentRemoteMediaClient()?.play()
    }

    fun pauseOnCast() {
        currentRemoteMediaClient()?.pause()
    }

    // セッション終了リスナー
    private val sessionEndedListeners = mutableListOf<() -> Unit>()

    /**
     * セッション終了時のリスナーを追加する。
     */
    fun addSessionEndedListener(listener: () -> Unit) {
        sessionEndedListeners.add(listener)
    }

    fun removeSessionEndedListener(listener: () -> Unit) {
        sessionEndedListeners.remove(listener)
    }

    /**
     * ウェブページからのセッションリクエストを処理する。
     * デバイス選択ダイアログを表示し、結果をコールバックで返す。
     */
    fun requestSessionFromWeb(
        context: Context,
        callback: (success: Boolean, sessionId: String, deviceName: String) -> Unit,
    ) {
        Log.d(TAG, "requestSessionFromWeb")
        webSessionCallback = callback
        showChooserDialog(context)
    }

    /**
     * Cast デバイスにメッセージを送信する。
     */
    fun sendMessageOnCast(namespace: String, message: String) {
        val session = sessionManager?.currentCastSession ?: run {
            Log.w(TAG, "sendMessageOnCast: セッションなし")
            return
        }
        try {
            session.sendMessage(namespace, message)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessageOnCast失敗: namespace=$namespace", e)
        }
    }

    /**
     * Cast デバイスからのメッセージリスナーを登録する。
     */
    fun addMessageListenerOnCast(namespace: String, callback: (namespace: String, message: String) -> Unit) {
        val session = sessionManager?.currentCastSession ?: run {
            Log.w(TAG, "addMessageListenerOnCast: セッションなし")
            return
        }
        try {
            session.setMessageReceivedCallbacks(namespace, Cast.MessageReceivedCallback { _, ns, msg ->
                Log.d(TAG, "messageReceived: namespace=$ns")
                callback(ns, msg)
            })
        } catch (e: Exception) {
            Log.e(TAG, "addMessageListenerOnCast失敗: namespace=$namespace", e)
        }
    }

    /**
     * CastWebExtension 用のブリッジハンドラを作成する。
     */
    fun createBridgeHandler(activityContext: Context): CastWebExtension.CastBridgeHandler {
        return object : CastWebExtension.CastBridgeHandler {
            override fun requestSession(callback: (Boolean, String, String) -> Unit) {
                requestSessionFromWeb(activityContext, callback)
            }

            override fun sendMessage(namespace: String, message: String) {
                sendMessageOnCast(namespace, message)
            }

            override fun addMessageListener(namespace: String, callback: (String, String) -> Unit) {
                addMessageListenerOnCast(namespace, callback)
            }

            override fun stopSession() {
                stopCasting()
            }
        }
    }

    /**
     * リソースを解放する。ActivityのonDestroy等で呼ぶ。
     */
    fun cleanup() {
        sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
        castExecutor?.shutdown()
        castExecutor = null
        sessionEndedListeners.clear()
        scope.cancel()
    }

    private fun currentRemoteMediaClient() =
        sessionManager?.currentCastSession?.remoteMediaClient

    private fun loadMediaOnCast(
        session: CastSession,
        url: String,
        title: String,
        positionMs: Long,
    ) {
        val contentType = guessContentType(url)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, title.ifEmpty { "Media" })
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()
        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setCurrentTime(positionMs)
            .setAutoplay(true)
            .build()
        session.remoteMediaClient?.load(loadRequest)?.setResultCallback { result ->
            if (!result.status.isSuccess) {
                Log.e(TAG, "メディアのロード失敗: ${result.status.statusMessage}")
            } else {
                Log.d(TAG, "メディアのロード成功")
            }
        }
    }

    companion object {
        private const val TAG = "CastManager"

        /**
         * キャスト可能なURLかどうかを判定する。
         * blob:URLやdata:URIはChromecastでは再生できない。
         */
        fun isCastableUrl(url: String): Boolean {
            if (url.isBlank()) return false
            if (url.startsWith("blob:")) return false
            if (url.startsWith("data:")) return false
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            return true
        }

        private fun guessContentType(url: String): String {
            return when {
                url.contains(".mp4", ignoreCase = true) -> "video/mp4"
                url.contains(".webm", ignoreCase = true) -> "video/webm"
                url.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
                url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegurl"
                url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                url.contains(".ogg", ignoreCase = true) -> "video/ogg"
                else -> "video/mp4"
            }
        }
    }
}

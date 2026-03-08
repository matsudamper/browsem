package net.matsudamper.browser.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * GeckoViewのMediaSession.Delegateを実装し、
 * メディアイベントをMediaSessionBridgeに転送してAndroid通知と連携する。
 */
class GeckoMediaSessionDelegate(
    private val context: Context,
) : MediaSession.Delegate {

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onActivated")
        MediaSessionBridge.activeGeckoMediaSession = mediaSession
        MediaSessionBridge.activate()
        // サービス起動はonPlayまで遅延する。
        // onActivated時点ではisPlaying=falseのためMedia3がstartForeground()を
        // 呼ばず、ForegroundServiceDidNotStartInTimeExceptionが発生するため。
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onDeactivated")
        MediaSessionBridge.deactivate()
        context.stopService(Intent(context, MediaPlaybackService::class.java))
    }

    override fun onMetadata(
        session: GeckoSession,
        mediaSession: MediaSession,
        meta: MediaSession.Metadata,
    ) {
        Log.d(TAG, "onMetadata: title=${meta.title}, artist=${meta.artist}, hasArtwork=${meta.artwork != null}")
        // アートワークをGeckoViewのImage APIからBitmapとして取得
        val artwork = meta.artwork
        if (artwork != null) {
            artwork.getBitmap(256).accept { bitmap ->
                Log.d(TAG, "onMetadata: artworkBitmap received: $bitmap")
                MediaSessionBridge.updateMetadata(
                    title = meta.title ?: "",
                    artist = meta.artist ?: "",
                    album = meta.album ?: "",
                    artworkBitmap = bitmap,
                )
            }
        } else {
            MediaSessionBridge.updateMetadata(
                title = meta.title ?: "",
                artist = meta.artist ?: "",
                album = meta.album ?: "",
                artworkBitmap = null,
            )
        }
    }

    override fun onFeatures(
        session: GeckoSession,
        mediaSession: MediaSession,
        features: Long,
    ) {
        Log.d(TAG, "onFeatures: features=$features")
        MediaSessionBridge.updateFeatures(features)
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPlay")
        // isPlaying=trueにしてからサービスを起動することで、
        // Media3がSTATE_READY+isPlayingの状態を検知してstartForeground()を呼ぶ
        MediaSessionBridge.updatePlaying(isPlaying = true)
        val intent = Intent(context, MediaPlaybackService::class.java)
        startMediaPlaybackService(intent)
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPause")
        MediaSessionBridge.updatePlaying(isPlaying = false)
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onStop")
        MediaSessionBridge.deactivate()
        context.stopService(Intent(context, MediaPlaybackService::class.java))
    }

    override fun onPositionState(
        session: GeckoSession,
        mediaSession: MediaSession,
        state: MediaSession.PositionState,
    ) {
        Log.d(TAG, "onPositionState: position=${state.position}, duration=${state.duration}")
        MediaSessionBridge.updatePosition(
            positionMs = (state.position * 1000).toLong(),
            durationMs = (state.duration * 1000).toLong(),
        )
    }

    override fun onFullscreen(
        session: GeckoSession,
        mediaSession: MediaSession,
        enabled: Boolean,
        meta: MediaSession.ElementMetadata?,
    ) {
        // フルスクリーンは未対応
    }

    companion object {
        private const val TAG = "GeckoMediaSession"
    }

    private fun startMediaPlaybackService(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.startService(intent)
            return
        }

        // フォアグラウンド中の再生開始は通常 startService で十分。
        // バックグラウンド制約にかかった場合のみ startForegroundService にフォールバックする。
        runCatching {
            context.startService(intent)
        }.onFailure {
            Log.w(TAG, "startService failed, fallback to startForegroundService", it)
            intent.putExtra(MediaPlaybackService.EXTRA_REQUIRE_IMMEDIATE_FOREGROUND, true)
            context.startForegroundService(intent)
        }
    }
}

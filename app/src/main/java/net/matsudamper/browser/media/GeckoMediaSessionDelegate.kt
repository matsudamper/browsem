package net.matsudamper.browser.media

import android.content.Context
import android.content.Intent
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
        // フォアグラウンドサービスを開始
        val intent = Intent(context, MediaPlaybackService::class.java)
        context.startForegroundService(intent)
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
        MediaSessionBridge.updatePlaying(isPlaying = true)
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
}

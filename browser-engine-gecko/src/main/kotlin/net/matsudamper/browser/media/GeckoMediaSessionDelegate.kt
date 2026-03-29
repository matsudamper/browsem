package net.matsudamper.browser.media

import android.util.Log
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * GeckoViewのMediaSession.Delegateを実装し、
 * 制御用のMediaSession参照と feature 情報だけを保持する。
 */
class GeckoMediaSessionDelegate(
    private val mediaWebExtension: MediaWebExtension,
) : MediaSession.Delegate {

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onActivated")
        mediaWebExtension.onActivated(session, mediaSession)
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onDeactivated")
        mediaWebExtension.onDeactivated(session)
    }

    override fun onMetadata(
        session: GeckoSession,
        mediaSession: MediaSession,
        meta: MediaSession.Metadata,
    ) {
        Log.d(TAG, "onMetadata: title=${meta.title}, artist=${meta.artist}, hasArtwork=${meta.artwork != null}")
        mediaWebExtension.onMetadata(session, meta)
    }

    override fun onFeatures(
        session: GeckoSession,
        mediaSession: MediaSession,
        features: Long,
    ) {
        Log.d(TAG, "onFeatures: features=$features")
        mediaWebExtension.onFeatures(session, features)
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPlay")
        mediaWebExtension.onPlay(session, mediaSession)
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPause")
        mediaWebExtension.onPause(session, mediaSession)
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onStop")
        mediaWebExtension.onDeactivated(session)
    }

    override fun onPositionState(
        session: GeckoSession,
        mediaSession: MediaSession,
        state: MediaSession.PositionState,
    ) {
        Log.d(TAG, "onPositionState: position=${state.position}, duration=${state.duration}")
        mediaWebExtension.onPositionState(session, mediaSession, state)
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

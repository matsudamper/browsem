package net.matsudamper.browser.feature.media

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
        Log.d(TAG, "onActivated: session=${session.logKey()} mediaSession=${mediaSession.logKey()}")
        MediaTraceLog.d("GECKO activated session=${session.logKey()} mediaSession=${mediaSession.logKey()}")
        mediaWebExtension.onActivated(session, mediaSession)
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(
            TAG,
            "onDeactivated: session=${session.logKey()} mediaSession=${mediaSession.logKey()} ignore Gecko deactivation and wait for WebExtension snapshot",
        )
        MediaTraceLog.d("GECKO deactivated session=${session.logKey()} mediaSession=${mediaSession.logKey()}")
    }

    override fun onMetadata(
        session: GeckoSession,
        mediaSession: MediaSession,
        meta: MediaSession.Metadata,
    ) {
        Log.d(
            TAG,
            "onMetadata: session=${session.logKey()} title=${meta.title}, artist=${meta.artist}, album=${meta.album}, hasArtwork=${meta.artwork != null}",
        )
        MediaTraceLog.d(
            "GECKO metadata session=${session.logKey()} title=${meta.title?.take(60)} artist=${meta.artist?.take(40)} hasArtwork=${meta.artwork != null}",
        )
        mediaWebExtension.onMetadata(session, meta)
    }

    override fun onFeatures(
        session: GeckoSession,
        mediaSession: MediaSession,
        features: Long,
    ) {
        Log.d(TAG, "onFeatures: session=${session.logKey()} features=$features")
        mediaWebExtension.onFeatures(session, features)
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPlay: session=${session.logKey()}")
        mediaWebExtension.onPlay(session, mediaSession)
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPause: session=${session.logKey()}")
        MediaTraceLog.d("GECKO pause session=${session.logKey()}")
        mediaWebExtension.onPause(session, mediaSession)
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(
            TAG,
            "onStop: session=${session.logKey()} mediaSession=${mediaSession.logKey()} ignore Gecko stop and wait for WebExtension snapshot",
        )
        MediaTraceLog.d("GECKO stop session=${session.logKey()} mediaSession=${mediaSession.logKey()}")
    }

    override fun onPositionState(
        session: GeckoSession,
        mediaSession: MediaSession,
        state: MediaSession.PositionState,
    ) {
        Log.d(
            TAG,
            "onPositionState: session=${session.logKey()} position=${state.position}, duration=${state.duration}, playbackRate=${state.playbackRate}",
        )
        MediaTraceLog.d(
            "GECKO position session=${session.logKey()} position=${state.position} duration=${state.duration} playbackRate=${state.playbackRate}",
        )
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

private fun Any.logKey(): String = Integer.toHexString(System.identityHashCode(this))

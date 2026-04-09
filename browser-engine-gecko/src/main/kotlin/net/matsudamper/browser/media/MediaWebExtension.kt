package net.matsudamper.browser.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * GeckoView の不安定な MediaSession 状態取得を避けるため、
 * ページ内の media 要素を監視する組み込み WebExtension。
 */
class MediaWebExtension(
    private val context: Context,
) {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionStates =
        Collections.synchronizedMap(WeakHashMap<GeckoSession, SessionPlaybackSnapshot>())
    private val sessionArtworkBitmaps =
        Collections.synchronizedMap(WeakHashMap<GeckoSession, Bitmap>())
    private val sessionArtworkRequestIds =
        Collections.synchronizedMap(WeakHashMap<GeckoSession, Long>())
    private val registeredSessions =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<GeckoSession, Boolean>()))
    private val artworkRequestSerial = AtomicLong(0L)
    private val artworkTargetSizePx by lazy(LazyThreadSafetyMode.NONE) {
        max(
            context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
            context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height),
        ).coerceAtLeast(DEFAULT_ARTWORK_SIZE_PX)
    }

    @Volatile
    private var activeSession: GeckoSession? = null
    private var pendingDeactivateSession: GeckoSession? = null
    private var pendingDeactivateRunnable: Runnable? = null

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    registeredSessions.toList().forEach { session ->
                        attachSessionMessageDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    fun registerSession(session: GeckoSession) {
        if (!registeredSessions.add(session)) {
            return
        }
        Log.d(TAG, "registerSession: session=${session.logKey()}")
        extension?.also { ext ->
            attachSessionMessageDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        Log.d(TAG, "unregisterSession: session=${session.logKey()} isOpen=${session.isOpen}")
        // メッセージデリゲートはセッション継続中のバックグラウンド再生にも必要なため維持する。
        if (activeSession === session && !session.isOpen) {
            deactivateSession(session)
        }
    }

    fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onActivated: session=${session.logKey()} mediaSession=${mediaSession.logKey()}")
        MediaTraceLog.d("WX activated session=${session.logKey()} mediaSession=${mediaSession.logKey()}")
        cancelPendingDeactivation(session)
        activeSession = session
        MediaSessionBridge.activeGeckoMediaSession = mediaSession
        applySessionState(session)
    }

    fun onDeactivated(session: GeckoSession) {
        Log.d(TAG, "onDeactivated: session=${session.logKey()}")
        MediaTraceLog.d("WX deactivated session=${session.logKey()}")
        deactivateSession(session)
    }

    fun onFeatures(session: GeckoSession, features: Long) {
        Log.d(TAG, "onFeatures: session=${session.logKey()} features=$features")
        cancelPendingDeactivation(session)
        val current = sessionStates[session] ?: SessionPlaybackSnapshot()
        val next = current.copy(features = features)
        sessionStates[session] = next
        promoteSessionFromSnapshotIfNeeded(session, next)
        if (activeSession === session) {
            MediaSessionBridge.updateFeatures(features)
        }
    }

    fun onMetadata(session: GeckoSession, meta: MediaSession.Metadata) {
        Log.d(
            TAG,
            "onMetadata: session=${session.logKey()} title=${meta.title}, artist=${meta.artist}, album=${meta.album}, hasArtwork=${meta.artwork != null}",
        )
        cancelPendingDeactivation(session)
        updateSessionSnapshot(session) { current ->
            current.copy(
                isActive = true,
                title = meta.title?.takeUnless { it.isBlank() } ?: current.title,
                artist = meta.artist?.takeUnless { it.isBlank() } ?: current.artist,
                album = meta.album?.takeUnless { it.isBlank() } ?: current.album,
            )
        }
        val artwork = meta.artwork
        val requestId = invalidateArtwork(session)
        if (artwork == null) {
            if (activeSession === session) {
                applySessionState(session)
            }
            return
        }

        artwork.getBitmap(artworkTargetSizePx).accept(
            { bitmap ->
                mainHandler.post {
                    if (!isArtworkRequestCurrent(session, requestId)) {
                        return@post
                    }
                    if (bitmap == null) {
                        sessionArtworkBitmaps.remove(session)
                    } else {
                        sessionArtworkBitmaps[session] = bitmap
                    }
                    if (activeSession === session) {
                        applySessionState(session)
                    }
                }
            },
            { error ->
                Log.w(TAG, "artwork getBitmap failed", error)
                mainHandler.post {
                    if (!isArtworkRequestCurrent(session, requestId)) {
                        return@post
                    }
                    sessionArtworkBitmaps.remove(session)
                    if (activeSession === session) {
                        applySessionState(session)
                    }
                }
            },
        )
    }

    fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPlay fallback: session=${session.logKey()}")
        cancelPendingDeactivation(session)
        bindMediaSessionIfNeeded(session, mediaSession)
        updateSessionSnapshot(session) { current ->
            current.copy(isActive = true, isPlaying = true)
        }
    }

    fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPause fallback: session=${session.logKey()}")
        MediaTraceLog.d("WX pauseFallback session=${session.logKey()}")
        cancelPendingDeactivation(session)
        bindMediaSessionIfNeeded(session, mediaSession)
        updateSessionSnapshot(session) { current ->
            current.copy(isActive = true, isPlaying = false)
        }
    }

    fun onPositionState(
        session: GeckoSession,
        mediaSession: MediaSession,
        state: MediaSession.PositionState,
    ) {
        Log.d(
            TAG,
            "onPositionState fallback: session=${session.logKey()} position=${state.position}, duration=${state.duration}",
        )
        MediaTraceLog.d(
            "WX positionFallback session=${session.logKey()} position=${state.position} duration=${state.duration}",
        )
        cancelPendingDeactivation(session)
        bindMediaSessionIfNeeded(session, mediaSession)
        updateSessionSnapshot(session) { current ->
            current.copy(
                isActive = true,
                positionMs = state.position.toSnapshotMillis(current.positionMs),
                durationMs = state.duration.toSnapshotMillis(current.durationMs),
            )
        }
    }

    fun isInstalled(): Boolean = extension != null

    fun shouldKeepSessionAttached(session: GeckoSession): Boolean {
        val snapshot = sessionStates[session]
        val keepAttached =
            activeSession === session &&
                (snapshot?.isActive ?: MediaSessionBridge.playbackState.value.isActive)
        Log.d(
            TAG,
            "shouldKeepSessionAttached: session=${session.logKey()} keepAttached=$keepAttached activeSession=${activeSession?.logKey()} snapshotActive=${snapshot?.isActive}",
        )
        return keepAttached
    }

    fun cleanup() {
        cancelPendingDeactivation()
        activeSession = null
        sessionStates.clear()
        sessionArtworkBitmaps.clear()
        sessionArtworkRequestIds.clear()
        registeredSessions.clear()
    }

    private fun attachSessionMessageDelegate(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    if (nativeApp != NATIVE_APP_ID) {
                        return null
                    }
                    val json = message as? JSONObject ?: return null
                    val previousSnapshot = sessionStates[session]
                    val isActive = json.optBoolean("isActive", false)
                    val isPlaying = json.optBoolean("isPlaying", false)
                    val incomingDurationMs = json.optLong("durationMs", 0L).coerceAtLeast(0L)
                    val incomingPositionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L)
                    val shouldKeepPreviousTiming =
                        isActive &&
                            incomingDurationMs == 0L &&
                            incomingPositionMs == 0L &&
                            previousSnapshot != null &&
                            previousSnapshot.durationMs > 0L
                    val snapshot = SessionPlaybackSnapshot(
                        isActive = isActive,
                        isPlaying = isPlaying,
                        title = json.optString("title", ""),
                        artist = json.optString("artist", ""),
                        album = json.optString("album", ""),
                        durationMs = if (shouldKeepPreviousTiming) previousSnapshot.durationMs else incomingDurationMs,
                        positionMs = if (shouldKeepPreviousTiming) previousSnapshot.positionMs else incomingPositionMs,
                        features = previousSnapshot?.features ?: 0L,
                    )
                    mainHandler.post {
                        Log.d(TAG, "raw snapshot: session=${session.logKey()} payload=$json")
                        val debugReason = json.optString("debugReason", "")
                        if (debugReason != "interval" && debugReason != "event:timeupdate") {
                            MediaTraceLog.d(
                                "WX raw session=${session.logKey()} reason=$debugReason isActive=${snapshot.isActive} isPlaying=${snapshot.isPlaying} " +
                                    "vis=${json.optString("debugVisibility", "")} playbackState=${json.optString("debugPlaybackState", "")} " +
                                    "mediaCount=${json.optInt("debugMediaCount", -1)} knownMedia=${json.optInt("debugKnownMediaCount", -1)} " +
                                    "paused=${json.optBoolean("debugMediaPaused", false)} ended=${json.optBoolean("debugMediaEnded", false)} " +
                                    "ready=${json.optInt("debugMediaReadyState", -1)} pos=${snapshot.positionMs} dur=${snapshot.durationMs} src=${json.optString("debugCurrentSrc", "").takeLast(80)}",
                            )
                        }
                        if (
                            previousSnapshot != null &&
                            (
                                previousSnapshot.title != snapshot.title ||
                                    previousSnapshot.artist != snapshot.artist ||
                                    previousSnapshot.album != snapshot.album
                                )
                        ) {
                            invalidateArtwork(session)
                        }
                        Log.d(
                            TAG,
                            "snapshot: session=${session.logKey()} isActive=${snapshot.isActive}, isPlaying=${snapshot.isPlaying}, " +
                                "title=${snapshot.title}, durationMs=${snapshot.durationMs}, positionMs=${snapshot.positionMs}, activeSession=${activeSession?.logKey()}",
                        )
                        if (snapshot.isActive) {
                            cancelPendingDeactivation(session)
                        }
                        sessionStates[session] = snapshot
                        promoteSessionFromSnapshotIfNeeded(session, snapshot)
                        if (activeSession === session) {
                            applySnapshot(session, snapshot)
                        }
                    }
                    return null
                }
            },
            NATIVE_APP_ID,
        )
    }

    private fun applySessionState(session: GeckoSession) {
        val snapshot = sessionStates[session]
        Log.d(
            TAG,
            "applySessionState: session=${session.logKey()} hasSnapshot=${snapshot != null} activeSession=${activeSession?.logKey()}",
        )
        if (snapshot != null) {
            applySnapshot(session, snapshot)
            return
        }

        MediaSessionBridge.activate()
        MediaSessionBridge.updateMetadata(
            title = "",
            artist = "",
            album = "",
            artworkBitmap = sessionArtworkBitmaps[session],
        )
        MediaSessionBridge.updatePlaying(false)
        MediaSessionBridge.updatePosition(positionMs = 0L, durationMs = 0L)
    }

    private fun updateSessionSnapshot(
        session: GeckoSession,
        transform: (SessionPlaybackSnapshot) -> SessionPlaybackSnapshot,
    ): SessionPlaybackSnapshot {
        val next = transform(sessionStates[session] ?: SessionPlaybackSnapshot())
        Log.d(
            TAG,
            "updateSessionSnapshot: session=${session.logKey()} next=$next activeSession=${activeSession?.logKey()}",
        )
        sessionStates[session] = next
        promoteSessionFromSnapshotIfNeeded(session, next)
        if (activeSession === session) {
            applySnapshot(session, next)
        }
        return next
    }

    private fun applySnapshot(session: GeckoSession, snapshot: SessionPlaybackSnapshot) {
        Log.d(
            TAG,
            "applySnapshot: session=${session.logKey()} snapshot=$snapshot activeSession=${activeSession?.logKey()} artwork=${sessionArtworkBitmaps.containsKey(session)}",
        )
        MediaTraceLog.d(
            "WX apply session=${session.logKey()} isActive=${snapshot.isActive} isPlaying=${snapshot.isPlaying} " +
                "titleBlank=${snapshot.title.isBlank()} pos=${snapshot.positionMs} dur=${snapshot.durationMs} artwork=${sessionArtworkBitmaps.containsKey(session)} active=${activeSession?.logKey()}",
        )
        if (!snapshot.isActive) {
            deactivateSession(session)
            return
        }

        MediaSessionBridge.activate()
        MediaSessionBridge.updateMetadata(
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            artworkBitmap = sessionArtworkBitmaps[session],
        )
        MediaSessionBridge.updatePosition(
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
        )
        MediaSessionBridge.updateFeatures(snapshot.features)
        MediaSessionBridge.updatePlaying(snapshot.isPlaying)
        if (snapshot.isActive) {
            MediaPlaybackServiceController.start(context)
        }
    }

    private fun bindMediaSessionIfNeeded(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(
            TAG,
            "bindMediaSessionIfNeeded: session=${session.logKey()} mediaSession=${mediaSession.logKey()} currentActive=${activeSession?.logKey()}",
        )
        if (activeSession == null || activeSession === session) {
            activeSession = session
            MediaSessionBridge.activeGeckoMediaSession = mediaSession
        }
    }

    private fun deactivateSession(session: GeckoSession) {
        Log.d(
            TAG,
            "deactivateSession: session=${session.logKey()} currentActive=${activeSession?.logKey()}",
        )
        MediaTraceLog.d("WX deactivate session=${session.logKey()} currentActive=${activeSession?.logKey()}")
        if (activeSession !== session) {
            return
        }
        scheduleDeactivation(session)
    }

    private fun invalidateArtwork(session: GeckoSession): Long {
        val requestId = artworkRequestSerial.incrementAndGet()
        sessionArtworkRequestIds[session] = requestId
        sessionArtworkBitmaps.remove(session)
        return requestId
    }

    private fun promoteSessionFromSnapshotIfNeeded(
        session: GeckoSession,
        snapshot: SessionPlaybackSnapshot,
    ) {
        if (!snapshot.isActive) {
            return
        }
        cancelPendingDeactivation(session)
        if (activeSession == null) {
            Log.d(TAG, "activeSession を WebExtension snapshot から補完: session=${session.logKey()}")
            activeSession = session
        }
    }

    private fun isArtworkRequestCurrent(session: GeckoSession, requestId: Long): Boolean {
        return sessionArtworkRequestIds[session] == requestId
    }

    private fun scheduleDeactivation(session: GeckoSession) {
        if (pendingDeactivateSession === session && pendingDeactivateRunnable != null) {
            return
        }
        cancelPendingDeactivation()
        pendingDeactivateSession = session
        pendingDeactivateRunnable = Runnable {
            if (activeSession !== session) {
                return@Runnable
            }
            val snapshot = sessionStates[session]
            if (snapshot?.isActive == true) {
                return@Runnable
            }
            Log.d(
                TAG,
                "deactivateSession grace period elapsed: session=${session.logKey()}",
            )
            activeSession = null
            pendingDeactivateSession = null
            pendingDeactivateRunnable = null
            MediaSessionBridge.deactivate()
            MediaPlaybackServiceController.stop(context)
        }.also { runnable ->
            mainHandler.postDelayed(runnable, DEACTIVATION_GRACE_PERIOD_MS)
        }
    }

    private fun cancelPendingDeactivation(session: GeckoSession? = null) {
        val runnable = pendingDeactivateRunnable ?: return
        if (session != null && pendingDeactivateSession !== session) {
            return
        }
        mainHandler.removeCallbacks(runnable)
        pendingDeactivateRunnable = null
        pendingDeactivateSession = null
    }

    companion object {
        private const val TAG = "MediaWebExtension"
        private const val NATIVE_APP_ID = "mediaBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/media_bridge/"
        private const val DEFAULT_ARTWORK_SIZE_PX = 256
        private const val DEACTIVATION_GRACE_PERIOD_MS = 5_000L
    }
}

private fun Double.toSnapshotMillis(fallback: Long): Long {
    if (!isFinite()) {
        return fallback
    }
    return (coerceAtLeast(0.0) * 1000.0).roundToLong()
}

private fun Any.logKey(): String = Integer.toHexString(System.identityHashCode(this))

internal data class SessionPlaybackSnapshot(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val features: Long = 0L,
)

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
        extension?.also { ext ->
            attachSessionMessageDelegate(session, ext)
        }
    }

    fun unregisterSession(session: GeckoSession) {
        // メッセージデリゲートはセッション継続中のバックグラウンド再生にも必要なため維持する。
        if (activeSession === session && !session.isOpen) {
            deactivateSession(session)
        }
    }

    fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        activeSession = session
        MediaSessionBridge.activeGeckoMediaSession = mediaSession
        applySessionState(session)
    }

    fun onDeactivated(session: GeckoSession) {
        deactivateSession(session)
    }

    fun onFeatures(session: GeckoSession, features: Long) {
        val current = sessionStates[session] ?: SessionPlaybackSnapshot()
        val next = current.copy(features = features)
        sessionStates[session] = next
        if (activeSession === session) {
            MediaSessionBridge.updateFeatures(features)
        }
    }

    fun onMetadata(session: GeckoSession, meta: MediaSession.Metadata) {
        Log.d(
            TAG,
            "onMetadata: title=${meta.title}, artist=${meta.artist}, hasArtwork=${meta.artwork != null}",
        )
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

    fun isInstalled(): Boolean = extension != null

    fun cleanup() {
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
                    val snapshot = SessionPlaybackSnapshot(
                        isActive = json.optBoolean("isActive", false),
                        isPlaying = json.optBoolean("isPlaying", false),
                        title = json.optString("title", ""),
                        artist = json.optString("artist", ""),
                        album = json.optString("album", ""),
                        durationMs = json.optLong("durationMs", 0L).coerceAtLeast(0L),
                        positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                        features = sessionStates[session]?.features ?: 0L,
                    )
                    mainHandler.post {
                        val previousSnapshot = sessionStates[session]
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
                        sessionStates[session] = snapshot
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

    private fun applySnapshot(session: GeckoSession, snapshot: SessionPlaybackSnapshot) {
        if (!snapshot.isActive) {
            MediaSessionBridge.deactivate()
            MediaPlaybackServiceController.stop(context)
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
        if (snapshot.isPlaying) {
            MediaPlaybackServiceController.start(context)
        }
    }

    private fun deactivateSession(session: GeckoSession) {
        if (activeSession !== session) {
            return
        }
        activeSession = null
        MediaSessionBridge.deactivate()
        MediaPlaybackServiceController.stop(context)
    }

    private fun invalidateArtwork(session: GeckoSession): Long {
        val requestId = artworkRequestSerial.incrementAndGet()
        sessionArtworkRequestIds[session] = requestId
        sessionArtworkBitmaps.remove(session)
        return requestId
    }

    private fun isArtworkRequestCurrent(session: GeckoSession, requestId: Long): Boolean {
        return sessionArtworkRequestIds[session] == requestId
    }

    companion object {
        private const val TAG = "MediaWebExtension"
        private const val NATIVE_APP_ID = "mediaBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/media_bridge/"
        private const val DEFAULT_ARTWORK_SIZE_PX = 256
    }
}

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

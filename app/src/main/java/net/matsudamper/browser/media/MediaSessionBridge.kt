package net.matsudamper.browser.media

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.MediaSession

/**
 * GeckoViewのMediaSessionとAndroid MediaSessionServiceの間の橋渡しをするシングルトン。
 * 現在アクティブなGeckoView MediaSessionの参照を保持し、
 * メディア状態をStateFlowで公開する。
 */
object MediaSessionBridge {

    // 現在アクティブなGeckoView MediaSessionの参照
    var activeGeckoMediaSession: MediaSession? = null

    private val _playbackState = MutableStateFlow(MediaPlaybackState())

    // サービスが監視するメディア状態
    val playbackState: StateFlow<MediaPlaybackState> = _playbackState.asStateFlow()

    fun activate() {
        Log.d(TAG, "activate")
        _playbackState.value = _playbackState.value.copy(isActive = true)
    }

    fun deactivate() {
        Log.d(TAG, "deactivate")
        _playbackState.value = MediaPlaybackState()
        activeGeckoMediaSession = null
    }

    fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        artworkBitmap: Bitmap?,
    ) {
        Log.d(TAG, "updateMetadata: title=$title, artist=$artist, hasArtwork=${artworkBitmap != null}")
        _playbackState.value = _playbackState.value.copy(
            title = title,
            artist = artist,
            album = album,
            artworkBitmap = artworkBitmap,
            metadataVersion = _playbackState.value.metadataVersion + 1,
        )
    }

    fun updatePlaying(isPlaying: Boolean) {
        Log.d(TAG, "updatePlaying: isPlaying=$isPlaying")
        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
    }

    fun updatePosition(positionMs: Long, durationMs: Long) {
        _playbackState.value = _playbackState.value.copy(
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    fun updateFeatures(features: Long) {
        Log.d(TAG, "updateFeatures: features=$features")
        _playbackState.value = _playbackState.value.copy(features = features)
    }

    // サービスからGeckoViewへの制御メソッド
    fun play() {
        Log.d(TAG, "play")
        activeGeckoMediaSession?.play()
    }
    fun pause() {
        Log.d(TAG, "pause")
        activeGeckoMediaSession?.pause()
    }
    fun stop() {
        Log.d(TAG, "stop")
        activeGeckoMediaSession?.stop()
    }
    fun nextTrack() {
        Log.d(TAG, "nextTrack")
        activeGeckoMediaSession?.nextTrack()
    }
    fun previousTrack() {
        Log.d(TAG, "previousTrack")
        activeGeckoMediaSession?.previousTrack()
    }
    fun seekTo(positionSeconds: Double) {
        Log.d(TAG, "seekTo: positionSeconds=$positionSeconds")
        activeGeckoMediaSession?.seekTo(positionSeconds, true)
    }

    private const val TAG = "MediaSessionBridge"
}

// メディア再生状態を保持するデータクラス
data class MediaPlaybackState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkBitmap: Bitmap? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val features: Long = 0L,
    // updateMetadata()が呼ばれるたびにインクリメントされるバージョン番号。
    // サービス側でメタデータ変更を検知して通知を強制更新するために使用する。
    val metadataVersion: Int = 0,
)

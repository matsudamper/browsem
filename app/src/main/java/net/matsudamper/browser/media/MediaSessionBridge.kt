package net.matsudamper.browser.media

import android.graphics.Bitmap
import android.os.SystemClock
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

    // トラック変更直後のonPositionStateは前の曲のデータが来るため、
    // 一定時間内のupdatePositionをスキップする
    private var lastTrackChangeTimeMs = 0L

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
        val current = _playbackState.value
        // タイトルまたはアーティストが変わった場合のみ「トラック変更」とする。
        // GeckoViewは同じ曲に対してonMetadataを繰り返し呼ぶため、
        // 毎回トラック変更扱いにすると不要なリセットが発生する。
        val isNewTrack = title != current.title || artist != current.artist

        if (isNewTrack) {
            lastTrackChangeTimeMs = SystemClock.elapsedRealtime()
        }

        Log.d(TAG, "updateMetadata: title=$title, artist=$artist, hasArtwork=${artworkBitmap != null}, isNewTrack=$isNewTrack")
        _playbackState.value = current.copy(
            title = title,
            artist = artist,
            album = album,
            artworkBitmap = artworkBitmap,
            metadataVersion = if (isNewTrack) current.metadataVersion + 1 else current.metadataVersion,
            // 新しい曲の場合のみポジションをリセット。
            // durationはリセットしない（シークバーの表示を維持するため）。
            positionMs = if (isNewTrack) 0L else current.positionMs,
        )
    }

    fun updatePlaying(isPlaying: Boolean) {
        Log.d(TAG, "updatePlaying: isPlaying=$isPlaying")
        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
    }

    fun updatePosition(positionMs: Long, durationMs: Long) {
        // トラック変更直後のonPositionStateは前の曲の最終位置が報告されるためスキップする。
        // ログで確認: onPositionState は前の曲と完全に同じ position/duration を
        // トラック変更後約1秒で報告してくる。2秒のマージンで確実にフィルタする。
        val timeSinceTrackChange = SystemClock.elapsedRealtime() - lastTrackChangeTimeMs
        if (timeSinceTrackChange < TRACK_CHANGE_POSITION_IGNORE_MS) {
            Log.d(TAG, "updatePosition: skipped (${timeSinceTrackChange}ms since track change)")
            return
        }
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
    private const val TRACK_CHANGE_POSITION_IGNORE_MS = 2000L
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
    // トラックが実際に変わった時のみインクリメントされるバージョン番号。
    // サービス側でトラック変更を検知して通知を強制更新するために使用する。
    val metadataVersion: Int = 0,
)

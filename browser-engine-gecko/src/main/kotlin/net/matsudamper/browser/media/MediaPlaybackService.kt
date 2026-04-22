package net.matsudamper.browser.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * メディア再生通知を管理するフォアグラウンドサービス。
 * Media3のMediaSessionServiceを継承し、GeckoViewのメディア再生を
 * Android標準のメディアコントロールと連携させる。
 */
class MediaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val hasStartedForeground = AtomicBoolean(false)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: bridgeState=${MediaSessionBridge.playbackState.value}")
        ensureMediaNotificationChannel()

        // 既存チャンネルを使用するようMedia3の通知プロバイダを設定。
        // onActivated()でactivate()を呼んでからサービス起動しているため、
        // Player初期状態はSTATE_READYになりMedia3が通知を発行する。
        val defaultNotificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .build()
        setMediaNotificationProvider(
            object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    customLayout: ImmutableList<androidx.media3.session.CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback,
                ): MediaNotification {
                    Log.d(
                        TAG,
                        "createNotification: title=${mediaSession.player.mediaMetadata.title} playbackState=${mediaSession.player.playbackState} playWhenReady=${mediaSession.player.playWhenReady} position=${mediaSession.player.currentPosition}",
                    )
                    MediaTraceLog.d(
                        "SVC createNotification title=${mediaSession.player.mediaMetadata.title?.toString()?.take(60)} " +
                            "playbackState=${mediaSession.player.playbackState} playWhenReady=${mediaSession.player.playWhenReady} position=${mediaSession.player.currentPosition}",
                    )
                    return defaultNotificationProvider.createNotification(
                        mediaSession,
                        customLayout,
                        actionFactory,
                        onNotificationChangedCallback,
                    )
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: android.os.Bundle,
                ): Boolean {
                    return defaultNotificationProvider.handleCustomCommand(session, action, extras)
                }

                override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo {
                    return defaultNotificationProvider.getNotificationChannelInfo()
                }
            }
        )

        // サービス起動前にonActivated()が呼ばれbridge状態にisActive=trueが反映済みのため、
        // 現在のBridge状態で初期化してSTATE_READYからスタートさせる
        val player = GeckoMediaPlayer()
        mediaSession = MediaSession.Builder(this, player).build().also { session ->
            addSession(session)
        }

        // Bridge状態を監視してPlayerの状態を更新
        serviceScope.launch {
            MediaSessionBridge.playbackState.collectLatest { state ->
                Log.d(TAG, "bridge state: isActive=${state.isActive}, isPlaying=${state.isPlaying}, title=${state.title}")
                player.updateFromBridge(state)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d(TAG, "onGetSession: packageName=${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: flags=$flags, startId=$startId bridgeState=${MediaSessionBridge.playbackState.value}")
        MediaTraceLog.d("SVC onStartCommand startId=$startId flags=$flags startedFg=${hasStartedForeground.get()}")

        if (!hasStartedForeground.compareAndSet(false, true)) {
            Log.d(TAG, "onStartCommand: skip bootstrap notification because foreground already started")
            MediaTraceLog.d("SVC skipBootstrap startId=$startId")
            return super.onStartCommand(intent, flags, startId)
        }

        // startForegroundService() 経由で起動された場合（フラグの有無に関わらず）、
        // タイムアウト回避のため先にプレースホルダ通知で foreground 化する。
        // Media3 が後から正式な通知で上書きする。
        try {
            startForeground(
                NOTIFICATION_ID,
                android.app.Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Media playback")
                    .setContentText("Starting…")
                    .setOngoing(true)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed (may not be a foreground service)", e)
            MediaTraceLog.d("SVC startForeground failed error=${e.javaClass.simpleName}")
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        MediaTraceLog.d("SVC onDestroy")
        hasStartedForeground.set(false)
        MediaPlaybackServiceController.onServiceDestroyed()
        mediaSession?.run {
            removeSession(this)
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureMediaNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "メディア再生",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MediaPlayback"
    }
}

/**
 * GeckoViewのメディア再生を制御するためのMedia3 Player実装。
 * 実際の再生はGeckoViewが行い、このPlayerは状態の反映と制御の橋渡しのみ行う。
 */
@OptIn(UnstableApi::class)
private class GeckoMediaPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    // サービス起動前にonActivated()→activate()が呼ばれているため、
    // 現在のBridge状態で初期化することでSTATE_READYからスタートできる
    private var currentState = MediaSessionBridge.playbackState.value

    fun updateFromBridge(state: MediaPlaybackState) {
        Log.d(PLAYER_TAG, "updateFromBridge: state=$state")
        MediaTraceLog.d(
            "PLY updateFromBridge isActive=${state.isActive} isPlaying=${state.isPlaying} titleBlank=${state.title.isBlank()} pos=${state.positionMs} dur=${state.durationMs}",
        )
        currentState = state
        invalidateState()
    }

    override fun getState(): State {
        val artworkBytes = currentState.artworkBitmap?.let { bitmapToBytes(it) }

        val metadata = MediaMetadata.Builder()
            .setTitle(currentState.title.ifEmpty { null })
            .setArtist(currentState.artist.ifEmpty { null })
            .setAlbumTitle(currentState.album.ifEmpty { null })
            .apply {
                if (artworkBytes != null) {
                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaMetadata(metadata)
            .build()

        val durationUs = if (currentState.durationMs > 0) {
            currentState.durationMs * 1000
        } else {
            C.TIME_UNSET
        }

        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .apply {
                if (currentState.features and org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK != 0L) {
                    add(Player.COMMAND_SEEK_TO_NEXT)
                    add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (currentState.features and org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK != 0L) {
                    add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
            }
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                currentState.isPlaying,
                PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(
                if (currentState.isActive) STATE_READY else STATE_IDLE,
            )
            .setContentPositionMs(currentState.positionMs)
            .setPlaylist(
                ImmutableList.of(
                    MediaItemData.Builder("gecko-media")
                        .setMediaItem(mediaItem)
                        .setMediaMetadata(metadata)
                        .setDurationUs(durationUs)
                        .build()
                )
            )
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            MediaSessionBridge.play()
        } else {
            MediaSessionBridge.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        MediaSessionBridge.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                MediaSessionBridge.nextTrack()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                MediaSessionBridge.previousTrack()
            }
            else -> {
                // 秒単位に変換してGeckoViewに渡す
                MediaSessionBridge.seekTo(positionMs / 1000.0)
            }
        }
        return Futures.immediateVoidFuture()
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    companion object {
        private const val PLAYER_TAG = "GeckoMediaPlayer"
    }
}

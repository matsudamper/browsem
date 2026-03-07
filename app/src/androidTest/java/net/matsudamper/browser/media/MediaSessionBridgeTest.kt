package net.matsudamper.browser.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.MediaSession

/**
 * MediaSessionBridgeの状態遷移を検証するインスツルメントテスト。
 *
 * MediaSessionBridgeはシングルトンのため、各テスト前後でdeactivate()でリセットする。
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionBridgeTest {

    @Before
    fun setUp() {
        MediaSessionBridge.deactivate()
    }

    @After
    fun tearDown() {
        MediaSessionBridge.deactivate()
    }

    @Test
    fun 初期状態はisActiveがfalse() {
        val state = MediaSessionBridge.playbackState.value
        assertFalse(state.isActive)
        assertFalse(state.isPlaying)
        assertEquals("", state.title)
        assertEquals("", state.artist)
        assertEquals("", state.album)
        assertNull(state.artworkBitmap)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals(0L, state.features)
    }

    @Test
    fun activate後にisActiveがtrueになる() {
        MediaSessionBridge.activate()
        assertTrue(MediaSessionBridge.playbackState.value.isActive)
    }

    @Test
    fun deactivate後に全状態がリセットされる() {
        // 色々な状態を設定してからdeactivate
        MediaSessionBridge.activate()
        MediaSessionBridge.updatePlaying(isPlaying = true)
        MediaSessionBridge.updateMetadata(
            title = "テスト曲",
            artist = "テストアーティスト",
            album = "テストアルバム",
            artworkBitmap = null,
        )
        MediaSessionBridge.updatePosition(positionMs = 10_000L, durationMs = 200_000L)

        MediaSessionBridge.deactivate()

        val state = MediaSessionBridge.playbackState.value
        assertFalse(state.isActive)
        assertFalse(state.isPlaying)
        assertEquals("", state.title)
        assertEquals("", state.artist)
        assertEquals("", state.album)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertNull(MediaSessionBridge.activeGeckoMediaSession)
    }

    @Test
    fun updateMetadataでタイトル_アーティスト_アルバムが更新される() {
        MediaSessionBridge.updateMetadata(
            title = "テスト曲",
            artist = "テストアーティスト",
            album = "テストアルバム",
            artworkBitmap = null,
        )
        val state = MediaSessionBridge.playbackState.value
        assertEquals("テスト曲", state.title)
        assertEquals("テストアーティスト", state.artist)
        assertEquals("テストアルバム", state.album)
        assertNull(state.artworkBitmap)
    }

    @Test
    fun updateMetadataで既存フィールドが上書きされる() {
        MediaSessionBridge.updateMetadata(
            title = "曲1",
            artist = "アーティスト1",
            album = "アルバム1",
            artworkBitmap = null,
        )
        MediaSessionBridge.updateMetadata(
            title = "曲2",
            artist = "アーティスト2",
            album = "アルバム2",
            artworkBitmap = null,
        )
        val state = MediaSessionBridge.playbackState.value
        assertEquals("曲2", state.title)
        assertEquals("アーティスト2", state.artist)
    }

    @Test
    fun updatePlayingでisPlayingがtrueになる() {
        MediaSessionBridge.updatePlaying(isPlaying = true)
        assertTrue(MediaSessionBridge.playbackState.value.isPlaying)
    }

    @Test
    fun updatePlayingでisPlayingがfalseになる() {
        MediaSessionBridge.updatePlaying(isPlaying = true)
        MediaSessionBridge.updatePlaying(isPlaying = false)
        assertFalse(MediaSessionBridge.playbackState.value.isPlaying)
    }

    @Test
    fun updatePositionで再生位置と長さが更新される() {
        MediaSessionBridge.updatePosition(positionMs = 30_000L, durationMs = 180_000L)
        val state = MediaSessionBridge.playbackState.value
        assertEquals(30_000L, state.positionMs)
        assertEquals(180_000L, state.durationMs)
    }

    @Test
    fun updateFeaturesでフィーチャーフラグが更新される() {
        val features = MediaSession.Feature.NEXT_TRACK or MediaSession.Feature.PREVIOUS_TRACK
        MediaSessionBridge.updateFeatures(features)
        assertEquals(features, MediaSessionBridge.playbackState.value.features)
    }

    @Test
    fun isActiveを維持したままメタデータ更新ができる() {
        MediaSessionBridge.activate()
        MediaSessionBridge.updateMetadata(
            title = "再生中",
            artist = "アーティスト",
            album = "アルバム",
            artworkBitmap = null,
        )
        val state = MediaSessionBridge.playbackState.value
        // isActiveは維持されている
        assertTrue(state.isActive)
        assertEquals("再生中", state.title)
    }

    @Test
    fun StateFlowがactivate後に新しい値を発行する() = runBlocking {
        MediaSessionBridge.activate()
        // StateFlowの現在値がisActive=trueになるまで待機
        val state = MediaSessionBridge.playbackState.first { it.isActive }
        assertNotNull(state)
        assertTrue(state.isActive)
    }

    @Test
    fun StateFlowがupdatePlaying後に新しい値を発行する() = runBlocking {
        MediaSessionBridge.updatePlaying(isPlaying = true)
        val state = MediaSessionBridge.playbackState.first { it.isPlaying }
        assertTrue(state.isPlaying)
    }
}

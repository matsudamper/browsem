package net.matsudamper.browser.media

import net.matsudamper.browser.feature.media.MediaPlaybackService
import net.matsudamper.browser.feature.media.MediaPlaybackState
import net.matsudamper.browser.feature.media.MediaSessionBridge
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.AutoplayPermissionDialogTestTags
import net.matsudamper.browser.GeckoBrowserTabTestTags
import net.matsudamper.browser.LocalHttpServer
import net.matsudamper.browser.MainActivity
import net.matsudamper.browser.openUrlFromUrlBar
import net.matsudamper.browser.openUrlViaViewIntent
import net.matsudamper.browser.waitForTabsScreenLoaded
import net.matsudamper.browser.waitForUrlBarContains
import net.matsudamper.browser.waitForUrlBarNotFocused
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaPrimarySelectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val timeoutRule: Timeout = Timeout.millis(TEST_TIMEOUT_MS)

    private var localHttpServer: LocalHttpServer? = null

    @Before
    fun setUp() {
        MediaSessionBridge.deactivate()
    }

    @After
    fun tearDown() {
        localHttpServer?.close()
        localHttpServer = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { MediaSessionBridge.deactivate() }
            runCatching { composeRule.activity.stopService(Intent(composeRule.activity, MediaPlaybackService::class.java)) }
            latch.countDown()
        }
        assertTrue("tearDown がタイムアウトしました", latch.await(5, TimeUnit.SECONDS))
    }

    /**
     * メディアの再生状態はビルトイン拡張のコンテンツスクリプト経由で取得する。
     * GeckoView 153 以降は拡張機能が file URL へアクセスできないため、
     * ページはループバックの HTTP サーバーから配信する(LocalHttpServer 参照)。
     */
    @Test
    fun 再生中の別media要素へ切り替わった時はpositionが新しい要素に追従する() {
        val mediaPageUri = startMediaPageServer(LOCAL_MEDIA_PLAYLIST_FILE_NAME)

        openMediaPage(mediaPageUri)
        ensureMediaPlaybackStarted()

        val firstTrackState =
            waitUntil(timeoutMs = FIRST_TRACK_TIMEOUT_MS) { state ->
                state.title == EXPECTED_FIRST_TITLE && state.positionMs >= MIN_FIRST_TRACK_POSITION_MS
            }
                ?.also { assertEquals(EXPECTED_FIRST_TITLE, it.title) }
                ?: throw AssertionError(
                    "1曲目のpositionを取得できない (current=${MediaSessionBridge.playbackState.value})",
                )

        val secondTrackState =
            waitUntil(timeoutMs = TRACK_SWITCH_TIMEOUT_MS) { state ->
                state.title == EXPECTED_SECOND_TITLE &&
                    state.positionMs + MIN_POSITION_RESET_DELTA_MS <= firstTrackState.positionMs
            } ?: throw AssertionError(
                "2曲目へ切り替わってもpositionが十分に巻き戻らない " +
                    "(track1=${firstTrackState.positionMs}, current=${MediaSessionBridge.playbackState.value.positionMs})",
            )

        assertEquals(EXPECTED_SECOND_TITLE, secondTrackState.title)
        assertTrue(
            "2曲目へ切り替わってもpositionが十分に巻き戻らない",
            secondTrackState.positionMs + MIN_POSITION_RESET_DELTA_MS <= firstTrackState.positionMs,
        )
    }

    private fun openMediaPage(mediaPageUri: String) {
        ensureBrowserScreen()
        composeRule.openUrlViaViewIntent(mediaPageUri)
        val openedByIntent =
            runCatching {
                composeRule.waitForUrlBarContains(LOCAL_MEDIA_PLAYLIST_FILE_NAME, timeoutMillis = 20_000)
                true
            }.getOrDefault(false)
        if (!openedByIntent) {
            composeRule.openUrlFromUrlBar(mediaPageUri)
            composeRule.waitForUrlBarContains(LOCAL_MEDIA_PLAYLIST_FILE_NAME, timeoutMillis = 60_000)
        }
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
    }

    /**
     * テスト用メディアページをキャッシュへ展開し、
     * ループバック HTTP サーバーから配信してその URL を返す。
     */
    private fun startMediaPageServer(indexFileName: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, LOCAL_MEDIA_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        assetManager.list(LOCAL_MEDIA_ASSET_DIR)?.forEach { name ->
            val destination = File(destinationDir, name)
            assetManager.open("$LOCAL_MEDIA_ASSET_DIR/$name").use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        val server = LocalHttpServer(destinationDir)
        localHttpServer = server
        return server.url(indexFileName)
    }

    private fun ensureBrowserScreen() {
        val toolbarReady =
            runCatching {
                composeRule.waitForUrlBarNotFocused(timeoutMillis = 5_000)
                true
            }.getOrDefault(false)
        if (toolbarReady) return

        val tabsReady =
            runCatching {
                composeRule.waitForTabsScreenLoaded(timeoutMillis = 10_000)
                true
            }.getOrDefault(false)
        if (tabsReady) {
            composeRule.onNodeWithTag(TabsScreenTestTags.AddTabButton.testTag).performClick()
            composeRule.waitForIdle()
        }
    }

    private fun ensureMediaPlaybackStarted() {
        // 可聴 autoplay の確認ダイアログが出ていると play() が GeckoResult 待ちのまま進まない。
        // CI ではダイアログを閉じずにタップしても再生が始まらず、tearDown で
        // allow=false になるまで 30 秒近く滞留した。
        confirmAutoplayPermissionIfShown()
        waitUntil(timeoutMs = AUTOSTART_GRACE_PERIOD_MS, ::hasPlaybackStarted)?.let {
            return
        }
        runCatching {
            composeRule.waitUntil(timeoutMillis = AUTOPLAY_DIALOG_WAIT_MS) {
                hasPlaybackStarted(MediaSessionBridge.playbackState.value) ||
                    isAutoplayPermissionDialogVisible()
            }
        }
        confirmAutoplayPermissionIfShown()
        waitUntil(timeoutMs = PLAYBACK_START_CONFIRM_TIMEOUT_MS, ::hasPlaybackStarted)?.let {
            return
        }
        val geckoNode = composeRule.onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
        repeat(PLAYBACK_TAP_RETRY_COUNT) {
            confirmAutoplayPermissionIfShown()
            geckoNode.performTouchInput { click() }
            composeRule.waitForIdle()
            waitUntil(timeoutMs = PLAYBACK_START_CONFIRM_TIMEOUT_MS, ::hasPlaybackStarted)?.let {
                return
            }
        }
        throw AssertionError(
            "タップ後も再生が開始されない state=${MediaSessionBridge.playbackState.value}",
        )
    }

    /**
     * 自動再生の確認ダイアログが出ていれば「今回のみ許可」を押す。
     * 永続化しないので他テストのサイト設定を汚さない。
     */
    private fun confirmAutoplayPermissionIfShown() {
        if (!isAutoplayPermissionDialogVisible()) return
        runCatching {
            composeRule
                .onNodeWithTag(AutoplayPermissionDialogTestTags.AllowOnce.testTag)
                .performClick()
            composeRule.waitForIdle()
        }
    }

    private fun isAutoplayPermissionDialogVisible(): Boolean {
        return composeRule
            .onAllNodesWithTag(AutoplayPermissionDialogTestTags.AllowOnce.testTag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()
    }

    /**
     * playlist.html は loadedmetadata 内で currentTime=30 / updateMetadata('Track 1') を
     * play() より前に実行する。そのため title・positionMs・isActive は autoplay が
     * ブロックされて paused のままでも true になり、tap によるユーザージェスチャ補填を
     * スキップしてしまう。実際に再生されている isPlaying のみを信頼する。
     */
    private fun hasPlaybackStarted(state: MediaPlaybackState): Boolean {
        return state.isPlaying
    }

    private fun waitUntil(
        timeoutMs: Long,
        predicate: (MediaPlaybackState) -> Boolean,
    ): MediaPlaybackState? {
        val startTime = SystemClock.elapsedRealtime()
        var lastMatchedState: MediaPlaybackState? = null
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            val state = MediaSessionBridge.playbackState.value
            if (predicate(state)) {
                lastMatchedState = state
                break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return lastMatchedState
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 180_000L
        private const val AUTOSTART_GRACE_PERIOD_MS = 2_000L
        private const val AUTOPLAY_DIALOG_WAIT_MS = 5_000L
        private const val PLAYBACK_TAP_RETRY_COUNT = 10
        private const val PLAYBACK_START_CONFIRM_TIMEOUT_MS = 2_500L
        private const val FIRST_TRACK_TIMEOUT_MS = 20_000L
        private const val TRACK_SWITCH_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val MIN_FIRST_TRACK_POSITION_MS = 30_000L
        private const val MIN_POSITION_RESET_DELTA_MS = 10_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_PLAYLIST_FILE_NAME = "playlist.html"
        private const val EXPECTED_FIRST_TITLE = "Track 1"
        private const val EXPECTED_SECOND_TITLE = "Track 2"
    }
}

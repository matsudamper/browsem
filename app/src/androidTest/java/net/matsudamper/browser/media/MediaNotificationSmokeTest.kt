package net.matsudamper.browser.media

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.MainActivity
import org.mozilla.gecko.util.GeckoBundle
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File

/**
 * メディア通知機能のスモークテスト。
 *
 * assets に埋め込んだテスト動画を GeckoView で再生し、
 * 独自WebExtensionによるメディア状態取得 → MediaSessionBridge → MediaPlaybackService
 * の一連のフローを検証する。
 */
@RunWith(AndroidJUnit4::class)
class MediaNotificationSmokeTest {
    private lateinit var activity: MainActivity

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val timeoutRule: Timeout = Timeout.millis(TEST_TIMEOUT_MS)

    @Before
    fun setUp() {
        activityRule.scenario.onActivity { launchedActivity ->
            activity = launchedActivity
        }
    }

    @After
    fun tearDown() {
        // GeckoView描画中でも後始末できるよう、UIスレッドで直接停止処理を行う。
        runOnMainThread {
            MediaSessionBridge.deactivate()
            runCatching {
                activity.stopService(Intent(activity, MediaPlaybackService::class.java))
            }
        }
    }

    /**
     * file:///android_asset/test-media/index.html を開き、
     * JavaScript でビデオ再生後に WebExtension 経由のメタデータと再生位置が反映されることを確認する。
     */
    @Test
    fun ローカル動画再生で拡張経由のメディア状態が反映される() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val mediaPageUri = prepareLocalMediaPageUri()
        MediaPlaybackService.resetGeneratedNotificationDebugState()
        allowAutoplayForTest()
        waitForMediaExtensionInstalled()

        // URL バー経由だと "https://file///..." に補正されるため、GeckoSession に直接 file URI を渡す。
        runOnMainThread {
            activeTab.session.loadUri(mediaPageUri)
        }
        Thread.sleep(PAGE_READY_DELAY_MS)

        // CI エミュレータではタップ入力が不安定な場合があるため、JSで再生要求をリトライする。
        val activationRetryDeadline = SystemClock.uptimeMillis() + SESSION_ACTIVATION_TIMEOUT_MS
        while (
            !MediaSessionBridge.playbackState.value.isActive &&
            SystemClock.uptimeMillis() < activationRetryDeadline
        ) {
            requestMediaPlayback(activeTab)
            Thread.sleep(PLAY_REQUEST_RETRY_INTERVAL_MS)
        }
        assertTrue(
            "ビデオ再生後に MediaSessionBridge がアクティブになること",
            MediaSessionBridge.playbackState.value.isActive,
        )

        // GeckoView の onPlay で isPlaying=true になるまで待機
        assertTrue(
            "MediaSessionBridge の isPlaying が true になること",
            waitForCondition(timeoutMs = PLAYBACK_STATE_TIMEOUT_MS) {
                MediaSessionBridge.playbackState.value.isPlaying
            },
        )
        assertTrue(
            "MediaSessionBridge の isPlaying が true になること (state=${MediaSessionBridge.playbackState.value})",
            MediaSessionBridge.playbackState.value.isPlaying,
        )

        assertTrue(
            "メタデータが拡張経由で反映されること",
            waitForCondition(timeoutMs = METADATA_TIMEOUT_MS) {
                val state = MediaSessionBridge.playbackState.value
                state.title == EXPECTED_TITLE &&
                    state.artist == EXPECTED_ARTIST &&
                    state.album == EXPECTED_ALBUM &&
                    state.artworkBitmap != null &&
                    state.durationMs > 0
            },
        )
        assertTrue(
            "再生位置が更新されること",
            waitForCondition(timeoutMs = POSITION_TIMEOUT_MS) {
                MediaSessionBridge.playbackState.value.positionMs > 0L
            },
        )

        val playbackState = MediaSessionBridge.playbackState.value
        assertTrue("title=${playbackState.title}", playbackState.title == EXPECTED_TITLE)
        assertTrue("artist=${playbackState.artist}", playbackState.artist == EXPECTED_ARTIST)
        assertTrue("album=${playbackState.album}", playbackState.album == EXPECTED_ALBUM)
        assertTrue("durationMs=${playbackState.durationMs}", playbackState.durationMs > 0L)
        assertTrue("positionMs=${playbackState.positionMs}", playbackState.positionMs > 0L)
        assertTrue("artworkBitmap=${playbackState.artworkBitmap}", playbackState.artworkBitmap != null)

        val hasMediaControlNotification = waitForMediaControlNotification(
            timeoutMs = NOTIFICATION_CONTROL_TIMEOUT_MS,
        )
        assertTrue(
            "メディア通知にコントロール（action）が表示されること\n" +
                "actionCount=${MediaPlaybackService.lastGeneratedNotificationActionCount}, " +
                "title=${MediaPlaybackService.lastGeneratedNotificationTitle}, " +
                "text=${MediaPlaybackService.lastGeneratedNotificationText}",
            hasMediaControlNotification,
        )
        assertTrue(
            "通知タイトルが拡張経由メタデータを反映すること: ${MediaPlaybackService.lastGeneratedNotificationTitle}",
            MediaPlaybackService.lastGeneratedNotificationTitle == EXPECTED_TITLE,
        )
        assertTrue(
            "通知に artwork が表示されること",
            waitForCondition(timeoutMs = NOTIFICATION_ARTWORK_TIMEOUT_MS) {
                MediaPlaybackService.lastGeneratedNotificationHasLargeIcon
            },
        )
        assertTrue(
            "通知に artwork が表示されること",
            MediaPlaybackService.lastGeneratedNotificationHasLargeIcon,
        )
    }

    // ================================================================
    // ヘルパーメソッド
    // ================================================================

    private fun waitForBrowserSessionController(): BrowserSessionController {
        return requireNotNull(
            waitForValue(timeoutMs = CONTROLLER_WAIT_TIMEOUT_MS) {
                runCatching {
                    runOnMainThread { getBrowserViewModel().browserSessionController }
                }.getOrNull()
            }
        )
    }

    private fun waitForActiveTab(browserSessionController: BrowserSessionController): BrowserTab {
        return requireNotNull(
            waitForValue(timeoutMs = ACTIVE_TAB_WAIT_TIMEOUT_MS) {
                runOnMainThread {
                    browserSessionController.tabs.firstOrNull { it.session.isOpen }
                        ?: browserSessionController.tabs.lastOrNull()
                }
            }
        )
    }

    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(activity)[BrowserViewModel::class.java]
    }

    private fun prepareLocalMediaPageUri(): String {
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
        return File(destinationDir, LOCAL_MEDIA_INDEX_FILE_NAME).toURI().toString()
    }

    private fun allowAutoplayForTest() {
        runOnMainThread {
            val runtime = getBrowserViewModel().runtime
            val prefs = GeckoBundle(2).apply {
                putInt("media.autoplay.default", 0)
                putInt("media.autoplay.blocking_policy", 0)
            }
            val setDefaultPrefsMethod =
                runtime.javaClass.getDeclaredMethod("setDefaultPrefs", GeckoBundle::class.java)
            setDefaultPrefsMethod.isAccessible = true
            setDefaultPrefsMethod.invoke(runtime, prefs)
        }
    }

    private fun waitForMediaExtensionInstalled() {
        assertTrue(
            "media WebExtension のインストールが完了すること",
            waitForCondition(timeoutMs = EXTENSION_INSTALL_TIMEOUT_MS) {
                runOnMainThread {
                    runCatching { getBrowserViewModel().mediaWebExtension.isInstalled() }
                        .getOrDefault(false)
                }
            },
        )
    }

    private fun hasMediaControlNotification(): Boolean {
        return MediaPlaybackService.lastGeneratedNotificationActionCount > 0
    }

    private fun waitForMediaControlNotification(
        timeoutMs: Long,
    ): Boolean {
        return waitForCondition(timeoutMs = timeoutMs) {
            hasMediaControlNotification()
        }
    }

    private fun requestMediaPlayback(activeTab: BrowserTab) {
        runOnMainThread {
            activeTab.session.loadUri(PLAYBACK_REQUEST_JAVASCRIPT_URI)
        }
    }

    private fun waitForCondition(
        timeoutMs: Long,
        intervalMs: Long = CONDITION_POLL_INTERVAL_MS,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(intervalMs)
        }
        return condition()
    }

    private fun <T> waitForValue(
        timeoutMs: Long,
        intervalMs: Long = CONDITION_POLL_INTERVAL_MS,
        provider: () -> T?,
    ): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            provider()?.let { return it }
            Thread.sleep(intervalMs)
        }
        return provider()
    }

    private fun <T> runOnMainThread(block: () -> T): T {
        var result: T? = null
        var throwable: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { block() }
                .onSuccess { result = it }
                .onFailure { throwable = it }
        }
        throwable?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 180_000L
        private const val CONTROLLER_WAIT_TIMEOUT_MS = 20_000L
        private const val ACTIVE_TAB_WAIT_TIMEOUT_MS = 20_000L
        private const val PAGE_READY_DELAY_MS = 3_000L
        private const val SESSION_ACTIVATION_TIMEOUT_MS = 30_000L
        private const val PLAY_REQUEST_RETRY_INTERVAL_MS = 1_000L
        private const val PLAYBACK_STATE_TIMEOUT_MS = 15_000L
        private const val NOTIFICATION_CONTROL_TIMEOUT_MS = 10_000L
        private const val METADATA_TIMEOUT_MS = 15_000L
        private const val POSITION_TIMEOUT_MS = 15_000L
        private const val NOTIFICATION_ARTWORK_TIMEOUT_MS = 10_000L
        private const val EXTENSION_INSTALL_TIMEOUT_MS = 20_000L
        private const val CONDITION_POLL_INTERVAL_MS = 200L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_INDEX_FILE_NAME = "index.html"
        private const val EXPECTED_TITLE = "Test Video"
        private const val EXPECTED_ARTIST = "Browsem"
        private const val EXPECTED_ALBUM = "AndroidTest Assets"
        private const val PLAYBACK_REQUEST_JAVASCRIPT_URI =
            "javascript:void((function(){" +
                "var media=document.querySelector('video,audio');" +
                "if(!media){return;}" +
                "var playPromise=media.play();" +
                "if(playPromise&&typeof playPromise.catch==='function'){playPromise.catch(function(){});}" +
                "})())"
    }
}

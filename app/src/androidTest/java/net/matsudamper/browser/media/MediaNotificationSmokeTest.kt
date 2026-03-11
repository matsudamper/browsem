package net.matsudamper.browser.media

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.MainActivity
import net.matsudamper.browser.TEST_TAG_GECKO_CONTAINER
import org.mozilla.gecko.util.GeckoBundle
import org.junit.After
import org.junit.Assert.assertTrue
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

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val timeoutRule: Timeout = Timeout.millis(TEST_TIMEOUT_MS)

    @After
    fun tearDown() {
        // テスト後にブリッジとサービスをリセット
        composeRule.runOnIdle {
            MediaSessionBridge.deactivate()
            composeRule.activity.stopService(
                Intent(composeRule.activity, MediaPlaybackService::class.java),
            )
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
        assertTrue(
            "ビデオ再生後に MediaSessionBridge がアクティブになること",
            startPlaybackWithRetry(activeTab = activeTab, mediaPageUri = mediaPageUri),
        )

        // 最終的に isActive になったことを確認
        composeRule.waitUntil(timeoutMillis = SESSION_ACTIVATION_TIMEOUT_MS) {
            MediaSessionBridge.playbackState.value.isActive
        }

        // GeckoView の onPlay で isPlaying=true になるまで待機
        composeRule.waitUntil(timeoutMillis = PLAYBACK_STATE_TIMEOUT_MS) {
            MediaSessionBridge.playbackState.value.isPlaying
        }
        assertTrue(
            "MediaSessionBridge の isPlaying が true になること",
            MediaSessionBridge.playbackState.value.isPlaying,
        )

        composeRule.waitUntil(timeoutMillis = METADATA_TIMEOUT_MS) {
            val state = MediaSessionBridge.playbackState.value
            state.title == EXPECTED_TITLE &&
                state.artist == EXPECTED_ARTIST &&
                state.album == EXPECTED_ALBUM &&
                state.durationMs > 0
        }
        composeRule.waitUntil(timeoutMillis = POSITION_TIMEOUT_MS) {
            MediaSessionBridge.playbackState.value.positionMs > 0L
        }

        val playbackState = MediaSessionBridge.playbackState.value
        assertTrue("title=${playbackState.title}", playbackState.title == EXPECTED_TITLE)
        assertTrue("artist=${playbackState.artist}", playbackState.artist == EXPECTED_ARTIST)
        assertTrue("album=${playbackState.album}", playbackState.album == EXPECTED_ALBUM)
        assertTrue("durationMs=${playbackState.durationMs}", playbackState.durationMs > 0L)
        assertTrue("positionMs=${playbackState.positionMs}", playbackState.positionMs > 0L)

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
    }

    // ================================================================
    // ヘルパーメソッド
    // ================================================================

    private fun waitForBrowserSessionController(): BrowserSessionController {
        var controller: BrowserSessionController? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var resolved = false
            composeRule.runOnIdle {
                resolved = runCatching {
                    controller = getBrowserViewModel().browserSessionController
                }.isSuccess
            }
            resolved
        }
        return requireNotNull(controller)
    }

    private fun waitForActiveTab(browserSessionController: BrowserSessionController): BrowserTab {
        var activeTab: BrowserTab? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var found = false
            composeRule.runOnIdle {
                activeTab = browserSessionController.tabs.firstOrNull { it.session.isOpen }
                    ?: browserSessionController.tabs.lastOrNull()
                found = activeTab != null
            }
            found
        }
        return requireNotNull(activeTab)
    }

    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
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
        composeRule.runOnIdle {
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
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var installed = false
            composeRule.runOnIdle {
                installed = runCatching { getBrowserViewModel().mediaWebExtension.isInstalled() }
                    .getOrDefault(false)
            }
            installed
        }
    }

    private fun startPlaybackWithRetry(
        activeTab: BrowserTab,
        mediaPageUri: String,
    ): Boolean {
        repeat(PLAYBACK_START_ATTEMPTS) { attempt ->
            loadMediaPage(activeTab, mediaPageUri)
            waitForActiveTabUrl(timeoutMillis = PAGE_LOAD_TIMEOUT_MS, activeTab = activeTab) { currentUrl ->
                currentUrl.startsWith("file:") && currentUrl.contains(LOCAL_MEDIA_INDEX_FILE_NAME)
            }
            Thread.sleep(PAGE_READY_DELAY_MS)

            val deadline = SystemClock.uptimeMillis() + SESSION_ACTIVATION_TIMEOUT_MS
            while (!MediaSessionBridge.playbackState.value.isActive && SystemClock.uptimeMillis() < deadline) {
                requestPlayback(activeTab)
                composeRule.onNodeWithTag(TEST_TAG_GECKO_CONTAINER).performTouchInput {
                    click(center)
                }
                Thread.sleep(CLICK_RETRY_INTERVAL_MS)
            }
            if (MediaSessionBridge.playbackState.value.isActive) {
                return true
            }
            if (attempt + 1 < PLAYBACK_START_ATTEMPTS) {
                Thread.sleep(PAGE_RELOAD_DELAY_MS)
            }
        }
        return MediaSessionBridge.playbackState.value.isActive
    }

    private fun loadMediaPage(activeTab: BrowserTab, mediaPageUri: String) {
        composeRule.runOnIdle {
            activeTab.session.loadUri(mediaPageUri)
        }
    }

    private fun requestPlayback(activeTab: BrowserTab) {
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:void((function(){" +
                    "var video=document.getElementById('video');" +
                    "if(video){" +
                    "var result=video.play();" +
                    "if(result&&result.catch){result.catch(function(){});}" +
                    "}" +
                    "})())",
            )
        }
    }

    private fun waitForActiveTabUrl(
        timeoutMillis: Long,
        activeTab: BrowserTab,
        predicate: (String) -> Boolean,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            var matched = false
            composeRule.runOnIdle {
                matched = predicate(activeTab.currentUrl)
            }
            matched
        }
    }

    private fun hasMediaControlNotification(): Boolean {
        return MediaPlaybackService.lastGeneratedNotificationActionCount > 0
    }

    private fun waitForMediaControlNotification(
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (hasMediaControlNotification()) {
                return true
            }
            Thread.sleep(200)
        }
        return false
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 90_000L
        private const val PAGE_READY_DELAY_MS = 3_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val PAGE_RELOAD_DELAY_MS = 1_500L
        private const val PLAYBACK_START_ATTEMPTS = 2
        private const val SESSION_ACTIVATION_TIMEOUT_MS = 30_000L
        private const val CLICK_RETRY_INTERVAL_MS = 2_000L
        private const val PLAYBACK_STATE_TIMEOUT_MS = 15_000L
        private const val NOTIFICATION_CONTROL_TIMEOUT_MS = 10_000L
        private const val METADATA_TIMEOUT_MS = 15_000L
        private const val POSITION_TIMEOUT_MS = 15_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_INDEX_FILE_NAME = "index.html"
        private const val EXPECTED_TITLE = "Test Video"
        private const val EXPECTED_ARTIST = "Browsem"
        private const val EXPECTED_ALBUM = "AndroidTest Assets"
    }
}

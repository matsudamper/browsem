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
 * GeckoView の MediaSession コールバック → MediaSessionBridge → MediaPlaybackService
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
     * JavaScript でビデオ再生後に MediaSessionBridge がアクティブ＆再生中になることを確認する。
     */
    @Test
    fun ローカル動画再生でメディアセッションが起動する() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val mediaPageUri = prepareLocalMediaPageUri()
        MediaPlaybackService.resetGeneratedNotificationDebugState()
        allowAutoplayForTest()

        // URL バー経由だと "https://file///..." に補正されるため、GeckoSession に直接 file URI を渡す。
        composeRule.runOnIdle {
            activeTab.session.loadUri(mediaPageUri)
        }
        Thread.sleep(PAGE_READY_DELAY_MS)
        composeRule.onNodeWithTag(TEST_TAG_GECKO_CONTAINER).performTouchInput {
            click(center)
        }

        // ユーザー操作で再生開始した後、GeckoView の onActivated が呼ばれて isActive になるまで待機
        composeRule.waitUntil(timeoutMillis = SESSION_ACTIVATION_TIMEOUT_MS) {
            MediaSessionBridge.playbackState.value.isActive
        }
        assertTrue(
            "ビデオ再生後に MediaSessionBridge がアクティブになること",
            MediaSessionBridge.playbackState.value.isActive,
        )

        // GeckoView の onPlay で isPlaying=true になるまで待機
        composeRule.waitUntil(timeoutMillis = PLAYBACK_STATE_TIMEOUT_MS) {
            MediaSessionBridge.playbackState.value.isPlaying
        }
        assertTrue(
            "MediaSessionBridge の isPlaying が true になること",
            MediaSessionBridge.playbackState.value.isPlaying,
        )

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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destinationDir = File(context.cacheDir, LOCAL_MEDIA_DIR_NAME).apply { mkdirs() }
        val assetManager = context.assets
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
        private const val TEST_TIMEOUT_MS = 30_000L
        private const val PAGE_READY_DELAY_MS = 1_000L
        private const val SESSION_ACTIVATION_TIMEOUT_MS = 12_000L
        private const val PLAYBACK_STATE_TIMEOUT_MS = 8_000L
        private const val NOTIFICATION_CONTROL_TIMEOUT_MS = 5_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_INDEX_FILE_NAME = "index.html"
    }
}

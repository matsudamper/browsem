package net.matsudamper.browser.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import net.matsudamper.browser.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * メディア通知機能のスモークテスト。
 *
 * ローカル動画ページを開いてユーザー操作で再生を開始し、
 * 通知シェードにメディアタイトルとコントロールが表示されることを確認する。
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
        // GeckoViewのメディア再生中にメインスレッドが応答しない場合でもテストがブロックしないよう、
        // CountDownLatchで最大5秒まで待機する非同期後始末にする。
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { MediaSessionBridge.deactivate() }
            runCatching { activity.stopService(Intent(activity, MediaPlaybackService::class.java)) }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    /**
     * file:///android_asset/test-media/index.html を開き、
     * 画面タップで再生開始した後に通知シェードへメディア通知が表示されることを確認する。
     */
    @Test
    fun ローカル動画再生でメディア通知が表示される() {
        val mediaPageUri = prepareLocalMediaPageUri()
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        openMediaPage(mediaPageUri)
        Thread.sleep(PAGE_READY_DELAY_MS)

        // ユーザー操作で再生を開始する（自動再生制限の影響を避ける）。
        repeat(PLAYBACK_TAP_RETRY_COUNT) {
            tapScreenCenter(uiDevice)
            Thread.sleep(PLAYBACK_TAP_INTERVAL_MS)
        }

        uiDevice.openNotification()
        try {
            assertTrue(
                "通知タイトルが表示されない",
                uiDevice.wait(Until.hasObject(By.text(EXPECTED_TITLE)), NOTIFICATION_CONTROL_TIMEOUT_MS),
            )
            assertTrue(
                "メディア通知にコントロール（Play/Pause）が表示されない",
                uiDevice.wait(Until.hasObject(By.descContains("Play")), NOTIFICATION_CONTROL_TIMEOUT_MS) ||
                    uiDevice.wait(Until.hasObject(By.descContains("Pause")), NOTIFICATION_CONTROL_TIMEOUT_MS),
            )
        } finally {
            uiDevice.pressBack()
        }
    }

    // ================================================================
    // ヘルパーメソッド
    // ================================================================

    private fun openMediaPage(mediaPageUri: String) {
        activityRule.scenario.onActivity { currentActivity ->
            currentActivity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse(mediaPageUri)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setClass(currentActivity, MainActivity::class.java)
                }
            )
        }
    }

    private fun tapScreenCenter(uiDevice: UiDevice) {
        val displayWidth = uiDevice.displayWidth
        val displayHeight = uiDevice.displayHeight
        uiDevice.click(displayWidth / 2, displayHeight / 2)
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

    companion object {
        private const val TEST_TIMEOUT_MS = 180_000L
        private const val PAGE_READY_DELAY_MS = 3_000L
        private const val PLAYBACK_TAP_RETRY_COUNT = 3
        private const val PLAYBACK_TAP_INTERVAL_MS = 1_500L
        private const val NOTIFICATION_CONTROL_TIMEOUT_MS = 15_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_INDEX_FILE_NAME = "index.html"
        private const val EXPECTED_TITLE = "Test Video"
    }
}

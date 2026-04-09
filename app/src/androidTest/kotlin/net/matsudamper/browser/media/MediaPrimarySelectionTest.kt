package net.matsudamper.browser.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import net.matsudamper.browser.MainActivity
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
        MediaSessionBridge.deactivate()
    }

    @After
    fun tearDown() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { MediaSessionBridge.deactivate() }
            runCatching { activity.stopService(Intent(activity, MediaPlaybackService::class.java)) }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    @Test
    fun 再生中の別media要素へ切り替わった時はpositionが新しい要素に追従する() {
        val mediaPageUri = prepareLocalMediaPageUri(LOCAL_MEDIA_PLAYLIST_FILE_NAME)
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        openMediaPage(mediaPageUri)
        Thread.sleep(PAGE_READY_DELAY_MS)
        tapScreenCenter(uiDevice)

        val switched =
            waitUntil(timeoutMs = TRACK_SWITCH_TIMEOUT_MS) {
                val state = MediaSessionBridge.playbackState.value
                state.title == EXPECTED_SECOND_TITLE && state.positionMs <= MAX_SECOND_TRACK_POSITION_MS
            }

        assertTrue("2曲目へ切り替わってもpositionがリセットされない", switched)
        assertEquals(EXPECTED_SECOND_TITLE, MediaSessionBridge.playbackState.value.title)
    }

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

    private fun prepareLocalMediaPageUri(indexFileName: String): String {
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
        return File(destinationDir, indexFileName).toURI().toString()
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            if (predicate()) {
                return true
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 180_000L
        private const val PAGE_READY_DELAY_MS = 3_000L
        private const val TRACK_SWITCH_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 250L
        private const val MAX_SECOND_TRACK_POSITION_MS = 2_000L
        private const val LOCAL_MEDIA_ASSET_DIR = "test-media"
        private const val LOCAL_MEDIA_DIR_NAME = "test-media"
        private const val LOCAL_MEDIA_PLAYLIST_FILE_NAME = "playlist.html"
        private const val EXPECTED_SECOND_TITLE = "Track 2"
    }
}

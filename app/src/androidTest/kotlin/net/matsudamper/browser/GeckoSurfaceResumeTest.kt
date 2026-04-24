package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class GeckoSurfaceResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun geckoPixelsRemainVisibleAfterActivityReturnsFromBackground() {
        val pageUri = prepareSurfaceResumePageUri()

        composeRule.openUrlFromUrlBar(pageUri)
        composeRule.waitForUrlBarContains(SURFACE_RESUME_FILE_NAME, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
        waitForGeckoContainer()
        waitForNonBlackGeckoPixels()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.waitForUrlBarContains(SURFACE_RESUME_FILE_NAME, timeoutMillis = 60_000)
        waitForGeckoContainer()
        waitForNonBlackGeckoPixels()
    }

    private fun waitForGeckoContainer() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForNonBlackGeckoPixels(timeoutMillis: Long = 30_000): Bitmap {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var latestBitmap: Bitmap? = null
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                latestBitmap = captureGeckoPixels()
                if (!latestBitmap.isMostlyBlack()) {
                    return latestBitmap
                }
            } catch (e: AssertionError) {
                // Activity リジューム直後は GeckoView の Compositor がまだ準備できておらず
                // capturePixels() が失敗することがある。一時的なエラーとしてリトライする。
                lastError = e
            }
            Thread.sleep(250L)
        }
        error(
            "GeckoView pixels stayed mostly black or capture failed. " +
                "lastBitmap=${latestBitmap?.width}x${latestBitmap?.height}, " +
                "lastError=$lastError",
        )
    }

    private fun captureGeckoPixels(): Bitmap {
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(composeRule.activity.window.decorView.findGeckoView()) {
                "GeckoView was not found in the activity view hierarchy"
            }.capturePixels().accept(
                { bitmap ->
                    bitmapRef.set(bitmap)
                    latch.countDown()
                },
                { error ->
                    errorRef.set(error)
                    latch.countDown()
                },
            )
        }

        assertTrue("Timed out waiting for GeckoView.capturePixels()", latch.await(10, TimeUnit.SECONDS))
        errorRef.get()?.let { throw AssertionError("GeckoView.capturePixels() failed", it) }
        return requireNotNull(bitmapRef.get()) {
            "GeckoView.capturePixels() returned null"
        }
    }

    private fun Bitmap.isMostlyBlack(): Boolean {
        val stepX = max(1, width / SAMPLE_GRID_SIZE)
        val stepY = max(1, height / SAMPLE_GRID_SIZE)
        var sampled = 0
        var black = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = getPixel(x, y)
                if (Color.alpha(pixel) > 0) {
                    sampled++
                    if (
                        Color.red(pixel) < BLACK_CHANNEL_THRESHOLD &&
                        Color.green(pixel) < BLACK_CHANNEL_THRESHOLD &&
                        Color.blue(pixel) < BLACK_CHANNEL_THRESHOLD
                    ) {
                        black++
                    }
                }
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && black.toDouble() / sampled >= BLACK_RATIO_THRESHOLD
    }

    private fun View.findGeckoView(): GeckoView? {
        if (this is GeckoView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findGeckoView()?.let { return it }
        }
        return null
    }

    private fun prepareSurfaceResumePageUri(): String {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val destinationDir = File(targetContext.cacheDir, SURFACE_RESUME_DIR_NAME).apply { mkdirs() }
        val destination = File(destinationDir, SURFACE_RESUME_FILE_NAME)
        destination.writeText(
            """
            <!doctype html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <title>Gecko Surface Resume</title>
                <style>
                  html, body {
                    margin: 0;
                    min-height: 100%;
                    background: #f8fafc;
                    color: #0f172a;
                    font: 24px sans-serif;
                  }
                  main {
                    min-height: 100vh;
                    padding: 32px;
                  }
                </style>
              </head>
              <body>
                <main>Gecko Surface Resume Test</main>
              </body>
            </html>
            """.trimIndent(),
        )
        return destination.toURI().toString()
    }

    private companion object {
        private const val SURFACE_RESUME_DIR_NAME = "surface-resume"
        private const val SURFACE_RESUME_FILE_NAME = "index.html"
        private const val SAMPLE_GRID_SIZE = 20
        private const val BLACK_CHANNEL_THRESHOLD = 16
        private const val BLACK_RATIO_THRESHOLD = 0.95
    }
}

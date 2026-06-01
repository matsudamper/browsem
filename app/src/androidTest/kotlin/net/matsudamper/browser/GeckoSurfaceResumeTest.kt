package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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

        Log.d(TAG, "ページロード開始: $pageUri")
        composeRule.openUrlFromUrlBar(pageUri)
        composeRule.waitForUrlBarContains(SURFACE_RESUME_FILE_NAME, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
        waitForGeckoContainer()
        Log.d(TAG, "初期レンダリング確認開始")
        waitForNonBlackGeckoPixels()
        Log.d(TAG, "初期レンダリング確認完了")

        Log.d(TAG, "バックグラウンド移行")
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Log.d(TAG, "フォアグラウンド復帰")
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // 復帰直後に URL バーがフォーカスを得ると urlInput が空にクリアされる。
        // GeckoContainer の復帰を待ってから検証する。waitForUrlBarContains は
        // フォーカス状態に依存せず現在ページ URL を読む。
        waitForGeckoContainer()
        composeRule.waitForUrlBarContains(SURFACE_RESUME_FILE_NAME, timeoutMillis = 60_000)
        Log.d(TAG, "復帰後レンダリング確認開始")
        waitForNonBlackGeckoPixels()
        Log.d(TAG, "復帰後レンダリング確認完了")
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
        val startTime = System.currentTimeMillis()
        var latestBitmap: Bitmap? = null
        var lastError: Throwable? = null
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                latestBitmap = captureGeckoPixels()
                val ratio = latestBitmap.blackRatio()
                Log.d(TAG, "試行${attempt}: 黒比率=${String.format("%.1f", ratio * 100)}%" +
                    " (${latestBitmap.width}x${latestBitmap.height})")
                if (ratio < BLACK_RATIO_THRESHOLD) {
                    Log.d(TAG, "非黒ピクセル確認完了 (試行${attempt}, 経過${System.currentTimeMillis() - startTime}ms)")
                    return latestBitmap
                }
            } catch (e: AssertionError) {
                // Activity リジューム直後は GeckoView の Compositor がまだ準備できておらず
                // capturePixels() が失敗することがある。一時的なエラーとしてリトライする。
                Log.w(TAG, "試行${attempt}: capturePixels失敗 - ${e.message}")
                lastError = e
            }
            Thread.sleep(250L)
        }
        val elapsed = System.currentTimeMillis() - startTime
        error(
            "GeckoView pixels stayed mostly black or capture failed after ${attempt} attempts (${elapsed}ms). " +
                "lastBitmap=${latestBitmap?.width}x${latestBitmap?.height}, " +
                "lastError=$lastError",
        )
    }

    private fun captureGeckoPixels(): Bitmap {
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val geckoView = composeRule.activity.window.decorView.findGeckoView()
            if (geckoView == null) {
                // GeckoView が見つからない場合にビュー階層を記録し、原因特定を容易にする。
                // Compose の AndroidView ラッパーがまだアタッチ中か、ライフサイクル復帰の
                // タイミングずれで一時的に除外されている可能性がある。
                val hierarchy = composeRule.activity.window.decorView.summarizeViewHierarchy(maxDepth = 4)
                errorRef.set(
                    AssertionError(
                        "GeckoView was not found in the activity view hierarchy.\n$hierarchy"
                    )
                )
                latch.countDown()
                return@runOnMainSync
            }
            geckoView.capturePixels().accept(
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
        errorRef.get()?.let { error ->
            // AssertionError はそのまま再throw して診断メッセージ（階層ダンプ等）を保持する。
            if (error is AssertionError) throw error
            throw AssertionError("GeckoView.capturePixels() failed", error)
        }
        return requireNotNull(bitmapRef.get()) {
            "GeckoView.capturePixels() returned null"
        }
    }

    /**
     * ビュー階層を `maxDepth` 段まで辿り、クラス名と可視状態(V/I/G)の1行サマリを返す。
     * GeckoView 不在時の診断用。
     */
    private fun View.summarizeViewHierarchy(maxDepth: Int, currentDepth: Int = 0): String {
        val indent = "  ".repeat(currentDepth)
        val visChar = when (visibility) {
            View.VISIBLE -> "V"
            View.INVISIBLE -> "I"
            View.GONE -> "G"
            else -> "?"
        }
        val self = "$indent${javaClass.simpleName}($visChar)"
        if (currentDepth >= maxDepth || this !is ViewGroup) return self
        val children = buildString {
            for (i in 0 until childCount) {
                append("\n")
                append(getChildAt(i).summarizeViewHierarchy(maxDepth, currentDepth + 1))
            }
        }
        return "$self$children"
    }

    private fun Bitmap.blackRatio(): Double {
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
        return if (sampled > 0) black.toDouble() / sampled else 0.0
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
        private const val TAG = "GeckoSurfaceResumeTest"
        private const val SURFACE_RESUME_DIR_NAME = "surface-resume"
        private const val SURFACE_RESUME_FILE_NAME = "index.html"
        private const val SAMPLE_GRID_SIZE = 20
        private const val BLACK_CHANNEL_THRESHOLD = 16
        private const val BLACK_RATIO_THRESHOLD = 0.95
    }
}

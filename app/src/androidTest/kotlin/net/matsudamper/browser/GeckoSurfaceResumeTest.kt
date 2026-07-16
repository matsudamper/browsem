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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class GeckoSurfaceResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun geckoPixelsRemainVisibleAfterActivityReturnsFromBackground() {
        LocalFixtureServer().use { server ->
            val pageUrl = server.pageUrl

            Log.d(TAG, "ページロード開始: $pageUrl")
            composeRule.openUrlFromUrlBar(pageUrl)
            composeRule.waitForUrlBarContains(SURFACE_RESUME_FILE_NAME, timeoutMillis = 60_000)
            composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
            waitForGeckoContainer()
            Log.d(TAG, "初期レンダリング確認開始")
            waitForFixtureBackgroundGeckoPixels()
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
            waitForFixtureBackgroundGeckoPixels()
            Log.d(TAG, "復帰後レンダリング確認完了")
        }
    }

    /**
     * Gemini 等のアシスタントオーバーレイのように、Activity を STOP させず pause だけして
     * 戻す focus-only 離脱を再現する。STARTED への遷移は ON_PAUSE のみを発火し ON_STOP は
     * 発火しない。
     *
     * IME 非表示の pause では surface を維持する (PAUSED_KEEP_SURFACE) ため、pause 中も
     * GeckoView のピクセルを取得でき続ける。過去に ON_PAUSE で常に releaseSession +
     * INVISIBLE していた頃は、この経路で surface が破棄され GeckoView が白画面化していた
     * (capturePixels も失敗していた)。本テストはそのデグレを検出する。
     */
    @Test
    fun geckoPixelsRemainVisibleWhilePausedWithoutStop() {
        LocalFixtureServer().use { server ->
            val pageUrl = server.pageUrl

            Log.d(TAG, "ページロード開始: $pageUrl")
            composeRule.openUrlFromUrlBar(pageUrl)
            composeRule.waitForUrlBarContains(SURFACE_RESUME_FILE_NAME, timeoutMillis = 60_000)
            composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
            waitForGeckoContainer()
            waitForFixtureBackgroundGeckoPixels()

            // STARTED = ON_PAUSE のみ (ON_STOP は発火しない) = アシスタントオーバーレイ相当。
            Log.d(TAG, "pause (STARTED) へ遷移")
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)

            // pause 中でも surface は維持されているので capturePixels が成功し続け、
            // フィクスチャの背景色が表示され続ける (真っ白な surface では失敗する)。
            Log.d(TAG, "pause 中のピクセル確認開始")
            waitForFixtureBackgroundGeckoPixels()
            Log.d(TAG, "pause 中のピクセル確認完了")

            // 復帰後も引き続き表示される。
            Log.d(TAG, "RESUMED へ復帰")
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitForGeckoContainer()
            waitForFixtureBackgroundGeckoPixels()
            Log.d(TAG, "復帰後レンダリング確認完了")
        }
    }

    private fun waitForGeckoContainer() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * フィクスチャページの背景色が一定比率以上表示されるまで待つ。
     * 「黒くない」だけの判定では真っ白な GeckoView (白画面デグレ) もパスしてしまうため、
     * フィクスチャ固有の背景色の比率を検証する。
     */
    private fun waitForFixtureBackgroundGeckoPixels(timeoutMillis: Long = 30_000): Bitmap {
        val deadline = System.currentTimeMillis() + timeoutMillis
        val startTime = System.currentTimeMillis()
        var latestBitmap: Bitmap? = null
        var lastError: Throwable? = null
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                latestBitmap = captureGeckoPixels()
                val ratio = latestBitmap.fixtureBackgroundRatio()
                Log.d(TAG, "試行${attempt}: 背景色比率=${String.format("%.1f", ratio * 100)}%" +
                    " (${latestBitmap.width}x${latestBitmap.height})")
                if (ratio >= FIXTURE_BACKGROUND_RATIO_THRESHOLD) {
                    Log.d(TAG, "背景色ピクセル確認完了 (試行${attempt}, 経過${System.currentTimeMillis() - startTime}ms)")
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
            "GeckoView pixels never showed the fixture background color after ${attempt} attempts (${elapsed}ms). " +
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

    /**
     * サンプリングした不透明ピクセルのうち、フィクスチャの背景色
     * (FIXTURE_BACKGROUND_*) に近い色が占める比率を返す。
     * 黒画面 (blackout) も白画面 (release 済み surface) もこの比率が 0 になるため、
     * どちらのデグレも検出できる。色管理による僅かな色ズレを許容するため
     * チャンネルごとに FIXTURE_CHANNEL_TOLERANCE の誤差を認める。
     */
    private fun Bitmap.fixtureBackgroundRatio(): Double {
        val stepX = max(1, width / SAMPLE_GRID_SIZE)
        val stepY = max(1, height / SAMPLE_GRID_SIZE)
        var sampled = 0
        var matched = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = getPixel(x, y)
                if (Color.alpha(pixel) > 0) {
                    sampled++
                    if (
                        abs(Color.red(pixel) - FIXTURE_BACKGROUND_RED) <= FIXTURE_CHANNEL_TOLERANCE &&
                        abs(Color.green(pixel) - FIXTURE_BACKGROUND_GREEN) <= FIXTURE_CHANNEL_TOLERANCE &&
                        abs(Color.blue(pixel) - FIXTURE_BACKGROUND_BLUE) <= FIXTURE_CHANNEL_TOLERANCE
                    ) {
                        matched++
                    }
                }
                x += stepX
            }
            y += stepY
        }
        return if (sampled > 0) matched.toDouble() / sampled else 0.0
    }

    private fun View.findGeckoView(): GeckoView? {
        if (this is GeckoView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findGeckoView()?.let { return it }
        }
        return null
    }

    /**
     * フィクスチャページを配信するローカル HTTP サーバー。
     *
     * 以前は cacheDir に書いた HTML を file: URI で開いていたが、URL バーの
     * buildUrlFromInput は http(s):// 以外を検索/https 付与として扱うため
     * `https://file:/...` になり OnLoadError の白いエラーページが表示されていた
     * (旧「非黒」検証はそれでもパスしていた)。実際にフィクスチャを描画させるため、
     * AboutBlankNewTabLocationTest と同じローカル HTTP 配信方式を使う。
     */
    private class LocalFixtureServer : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val serverThread = Thread {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    handleRequest(socket)
                }.onFailure {
                    if (!serverSocket.isClosed) {
                        Log.w(TAG, "local-http error=${it.message}")
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val pageUrl: String = "http://127.0.0.1:${serverSocket.localPort}/$SURFACE_RESUME_FILE_NAME"

        private fun handleRequest(socket: Socket) {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                reader.readLine() ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                // favicon 等の付随リクエストにも同じ HTML を返して構わない
                val bodyBytes = FIXTURE_HTML.toByteArray(Charsets.UTF_8)
                val output = client.getOutputStream()
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(headers.toByteArray(Charsets.US_ASCII))
                output.write(bodyBytes)
                output.flush()
            }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { serverThread.join(2_000) }
        }
    }

    private companion object {
        private const val TAG = "GeckoSurfaceResumeTest"
        private const val SURFACE_RESUME_FILE_NAME = "index.html"
        private const val SAMPLE_GRID_SIZE = 20

        private val FIXTURE_HTML = """
            <!doctype html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <title>Gecko Surface Resume</title>
                <style>
                  html, body {
                    margin: 0;
                    min-height: 100%;
                    /* テストが検証する背景色。FIXTURE_BACKGROUND_* と一致させること。
                       白でも黒でもない特徴的な色にして、release 済みの真っ白な surface や
                       blackout をどちらもデグレとして検出できるようにする。 */
                    background: rgb(61, 220, 132);
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
        """.trimIndent()

        // フィクスチャページの背景色 (FIXTURE_HTML の CSS と一致させること)
        private const val FIXTURE_BACKGROUND_RED = 61
        private const val FIXTURE_BACKGROUND_GREEN = 220
        private const val FIXTURE_BACKGROUND_BLUE = 132

        // 色管理・レンダリングによる僅かな色ズレの許容幅 (チャンネルごと)
        private const val FIXTURE_CHANNEL_TOLERANCE = 48

        // ページは背景色が大部分を占める (テキスト1行のみ) ため、
        // 半分以上が背景色ならフィクスチャが表示されていると判定する
        private const val FIXTURE_BACKGROUND_RATIO_THRESHOLD = 0.5
    }
}

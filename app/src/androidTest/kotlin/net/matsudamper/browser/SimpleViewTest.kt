package net.matsudamper.browser

import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.ui.browser.SimpleViewScreenTestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * シンプル表示 (Readability WebExtension) の動作確認テスト。
 *
 * ローカルHTTPページを記事ページとして開き、メニューから「シンプル表示」をタップすると
 * SimpleViewScreen オーバーレイが表示されることを確認する。
 */
@RunWith(AndroidJUnit4::class)
class SimpleViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var server: LocalHtmlServer? = null

    @Before
    fun setUp() {
        server = createLocalArticleServer()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    /**
     * 記事ページでシンプル表示をタップすると SimpleViewScreen が表示されることを確認する。
     */
    @Test
    fun tappingSimpleViewMenuShowsSimpleViewScreen() {
        val articlePageUrl = requireNotNull(server).indexUrl

        // 記事ページを読み込む
        composeRule.openUrlFromUrlBar(articlePageUrl)
        composeRule.waitForUrlBarContains(LOCAL_READABILITY_INDEX_FILE_NAME, timeoutMillis = 60_000)

        // document_idle が発火してコンテンツスクリプトがポートを確立するまで待機
        Thread.sleep(3_000)

        // メニューを開く
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()

        // 「シンプル表示」をタップ
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.SimpleViewMenuItem.testTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrowserToolbarMenuTestTags.SimpleViewMenuItem.testTag).performClick()

        // SimpleViewScreen が表示されるまで待機
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "シンプル表示画面が表示されていない",
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * シンプル表示を閉じると SimpleViewScreen が消えることを確認する。
     */
    @Test
    fun closingSimpleViewDismissesOverlay() {
        val articlePageUrl = requireNotNull(server).indexUrl

        composeRule.openUrlFromUrlBar(articlePageUrl)
        composeRule.waitForUrlBarContains(LOCAL_READABILITY_INDEX_FILE_NAME, timeoutMillis = 60_000)

        Thread.sleep(3_000)

        // シンプル表示を開く
        composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.MenuButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.SimpleViewMenuItem.testTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrowserToolbarMenuTestTags.SimpleViewMenuItem.testTag).performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isNotEmpty()
        }

        // 閉じるボタンをタップ
        composeRule.onNodeWithContentDescription("シンプル表示を閉じる").performClick()

        // SimpleViewScreen が消えるまで待機
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isEmpty()
        }

        assertTrue(
            "シンプル表示画面が閉じられていない",
            composeRule.onAllNodesWithTag(SimpleViewScreenTestTags.SimpleView.testTag).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun createLocalArticleServer(): LocalHtmlServer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val articleHtml = assets.open("$LOCAL_READABILITY_ASSET_DIR/$LOCAL_READABILITY_INDEX_FILE_NAME")
            .bufferedReader()
            .use { it.readText() }
        return LocalHtmlServer(articleHtml = articleHtml)
    }

    private class LocalHtmlServer(
        private val articleHtml: String,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val serverThread = Thread {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching { handleRequest(socket) }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val indexUrl: String = "http://127.0.0.1:${serverSocket.localPort}/$LOCAL_READABILITY_INDEX_FILE_NAME"

        private fun handleRequest(socket: Socket) {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val requestPath = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                val body = when (requestPath) {
                    "/", "/$LOCAL_READABILITY_INDEX_FILE_NAME" -> articleHtml
                    else -> "<!doctype html><html><head><title>Not Found</title></head><body>404</body></html>"
                }
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val output = client.getOutputStream()
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

    companion object {
        private const val LOCAL_READABILITY_ASSET_DIR = "test-readability"
        private const val LOCAL_READABILITY_INDEX_FILE_NAME = "index.html"
    }
}

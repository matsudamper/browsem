package net.matsudamper.browser

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
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
 * Web サイトの Notification.requestPermission() がアプリ側の PermissionDelegate を通じて
 * 正しく処理されることを確認するテスト。
 *
 * 再現手順（バグ）:
 *   1. 通知許可を要求するサイトを開く
 *   2. サイト側の JS が Notification.requestPermission() を呼び出す
 *   3. GeckoView の PermissionDelegate が呼ばれ GeckoResult が返される
 *   4. JS Promise が granted または denied で解決され、サイト側のダイアログが閉じる
 *
 * 期待（正常動作）:
 *   - JS Promise が解決され URL ハッシュが #permission-granted または #permission-denied になる
 *
 * 現状（バグ）:
 *   - Promise が pending のまま何も起きず、サイト側のダイアログが閉じない
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var server: NotificationPermissionTestServer? = null

    @Before
    fun setUp() {
        // POST_NOTIFICATIONS を事前に付与することで Android システムダイアログをスキップし、
        // GeckoView の PermissionDelegate → JS Promise 解決の流れを単独で検証する
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        server = NotificationPermissionTestServer()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    /**
     * Notification.requestPermission() が呼ばれたとき、PermissionDelegate が応答して
     * JS Promise が解決されることを確認する。
     *
     * バグがある場合: Promise が pending のままタイムアウトしてテストが失敗する。
     * 正常な場合: URL ハッシュが #permission-granted または #permission-denied になる。
     */
    @Test
    fun notificationPermissionRequestResolvesPromise() {
        val pageUrl = requireNotNull(server).indexUrl

        ensureBrowserScreen()
        composeRule.openUrlFromUrlBar(pageUrl)
        composeRule.waitForUrlBarContains(TEST_PAGE_FILE_NAME, timeoutMillis = 30_000)

        // Notification.requestPermission() の結果が URL ハッシュに反映されるまで待機する。
        // バグがある場合はここでタイムアウトする（Promise が永遠に pending のまま）。
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val url = composeRule.currentUrlBarText()
            url.contains("#permission-granted") || url.contains("#permission-denied")
        }

        val finalUrl = composeRule.currentUrlBarText()
        assertTrue(
            "Notification.requestPermission() の Promise が解決されなかった（URL ハッシュが更新されていない）: url=$finalUrl",
            finalUrl.contains("#permission-granted") || finalUrl.contains("#permission-denied"),
        )
    }

    private fun ensureBrowserScreen() {
        val browserReady = runCatching {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (browserReady) return

        composeRule.waitForTabsScreenLoaded()
        composeRule.onNodeWithTag(TabsScreenTestTags.AddTabButton.testTag).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 通知許可テスト用のローカル HTTP サーバー。
     *
     * サーブするページは DOMContentLoaded 後に Notification.requestPermission() を呼び出し、
     * Promise の結果を location.hash に反映する。
     */
    private inner class NotificationPermissionTestServer : AutoCloseable {
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

        val indexUrl: String = "http://127.0.0.1:${serverSocket.localPort}/$TEST_PAGE_FILE_NAME"

        private fun handleRequest(socket: Socket) {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                reader.readLine() // リクエスト行を読み捨て
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val bodyBytes = loadTestHtml().toByteArray(Charsets.UTF_8)
                val responseHeaders = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val output = client.getOutputStream()
                output.write(responseHeaders.toByteArray(Charsets.US_ASCII))
                output.write(bodyBytes)
                output.flush()
            }
        }

        private fun loadTestHtml(): String {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            return instrumentation.context.assets
                .open("$TEST_ASSET_DIR/$TEST_PAGE_FILE_NAME")
                .bufferedReader()
                .use { it.readText() }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { serverThread.join(2_000) }
        }
    }

    companion object {
        private const val TEST_ASSET_DIR = "test-notification-permission"
        private const val TEST_PAGE_FILE_NAME = "index.html"
    }
}

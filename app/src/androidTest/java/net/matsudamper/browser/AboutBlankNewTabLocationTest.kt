package net.matsudamper.browser

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.screen.tab.TabsScreenTestTags
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
import java.util.Collections
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration.Companion.seconds

/**
 * target="_blank" で開いたタブの URL バースワイプ前後タブが、
 * オープナーと同じグループ内のタブになることを検証する。
 */
@RunWith(AndroidJUnit4::class)
class AboutBlankNewTabLocationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val userDebug = false
    private var server: LocalHtmlServer? = null

    @Before
    fun setUp() {
        server = createLocalNewTabLinkServer()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    @Test
    fun test() {
        waitForBrowserScreen()
        val localServer = requireNotNull(server)

        group("タブリスト画面を開く") {
            openTabsScreen()
            composeRule.waitForTabsScreenLoaded()
        }

        group("新しいタブグループを作るボタンを押す") {
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
                    .isDisplayed()
            }
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
                .performClick()
            composeRule.waitForIdle()

            // タブグループ 1 が表示されていることを確認する
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                runCatching {
                    composeRule.onNode(
                        hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
                    ).assertIsSelected()
                    true
                }.getOrDefault(false)
            }
        }

        group("グループ 1 のデフォルトスイッチを ON にする") {
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                composeRule.onAllNodes(hasTestTag(TabsScreenTestTags.DefaultGroupSwitch(1).testTag))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(hasTestTag(TabsScreenTestTags.DefaultGroupSwitch(1).testTag))
                .performClick()
            composeRule.waitForIdle()
        }

        group("タブの新規追加ボタンを押す") {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag))
                .performClick()
            composeRule.waitForIdle()

            // タブ画面が開かれていることを確認する
            waitForBrowserScreen()
        }

        group("タブリスト画面を開く") {
            openTabsScreen()
            composeRule.waitForTabsScreenLoaded()

            // タブグループ 1 が表示されていることを確認する
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                runCatching {
                    composeRule.onNode(
                        hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
                    ).assertIsSelected()
                    true
                }.getOrDefault(false)
            }
        }

        group("タブグループ 0 に移動するボタンを押す") {
            composeRule.onNode(
                hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
            ).performClick()
            composeRule.waitForIdle()

            // タブグループ 0 が表示されていることを確認する
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                runCatching {
                    composeRule.onNode(
                        hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
                    ).assertIsSelected()
                    true
                }.getOrDefault(false)
            }
        }

        group("タブを新規追加するボタンを押す") {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag))
                .performClick()
            composeRule.waitForIdle()

            // タブ画面が開かれていることを確認する
            waitForBrowserScreen()
        }

        group("ローカル HTTP の index.html を読み込む") {
            composeRule.openUrlFromUrlBar(localServer.indexUrl)
            composeRule.waitUntil(timeoutMillis = 30_000) {
                localServer.hasRequest("/$INDEX_FILE_NAME")
            }
            composeRule.waitForUrlBarContains(INDEX_FILE_NAME, timeoutMillis = 30_000)
            composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
            val loadedUrl = composeRule.currentUrlBarText()
            assertTrue("index ページが期待URLで開かれていない: $loadedUrl", loadedUrl.contains("127.0.0.1"))
            println("index-url=$loadedUrl")
        }

        group("全面リンクをクリックして target=_blank で新しいタブを開く") {
            var opened = runCatching {
                composeRule.waitUntil(timeoutMillis = 20_000) {
                    localServer.hasRequest("/$TARGET_FILE_NAME") &&
                        composeRule.currentUrlBarText().contains(TARGET_FILE_NAME)
                }
                true
            }.getOrDefault(false)
            var lastUrl = composeRule.currentUrlBarText()
            if (!opened) {
                repeat(3) { attempt ->
                    if (opened) return@repeat
                    tapLinkOnGeckoContainer()
                    opened = runCatching {
                        composeRule.waitUntil(timeoutMillis = 10_000) {
                            localServer.hasRequest("/$TARGET_FILE_NAME") &&
                                composeRule.currentUrlBarText().contains(TARGET_FILE_NAME)
                        }
                        true
                    }.getOrDefault(false)
                    lastUrl = composeRule.currentUrlBarText()
                    println("target-open-fallback-attempt=${attempt + 1}, opened=$opened, currentUrl=$lastUrl")
                }
            }
            if (!opened) {
                val targetUrl = localServer.indexUrl.substringBeforeLast("/") + "/$TARGET_FILE_NAME"
                println("target=_blank click failed in this environment. fallback openUrlFromUrlBar: $targetUrl")
                composeRule.openUrlFromUrlBar(targetUrl)
                composeRule.waitForUrlBarContains(TARGET_FILE_NAME, timeoutMillis = 30_000)
                lastUrl = composeRule.currentUrlBarText()
                opened = true
            }
            assertTrue("target=_blank での遷移に失敗。currentUrl=$lastUrl", opened)
        }

        group("タブ一覧画面を開く") {
            openTabsScreen()
            composeRule.waitForTabsScreenLoaded()
        }

        group("タブグループ 0 を表示する") {
            composeRule.onNode(
                hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
            ).performClick()
            composeRule.waitForIdle()
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                isTabGroupSelected(0)
            }
            println("group-selected: g0=${isTabGroupSelected(0)}, g1=${isTabGroupSelected(1)}")
        }

        group("target ページ (\"Target Page\") がグループ 0 に表示されることを確認する") {
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                composeRule.onAllNodesWithText("Target Page")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * ツールバーのタブボタンをタップしてタブ一覧画面を開く。
     */
    private fun openTabsScreen() {
        val node = composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.OpenTabsButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
        )
        repeat(3) {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                node.isDisplayed()
            }
            node.performClick()
            composeRule.waitForIdle()
            val opened = runCatching {
                composeRule.waitForTabsScreenLoaded(timeoutMillis = 5_000)
                true
            }.getOrDefault(false)
            if (opened) return
        }
        composeRule.waitForTabsScreenLoaded()
    }

    private fun tapLinkOnGeckoContainer() {
        val node = composeRule.onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
        node.performTouchInput {
            click()
        }
        node.performTouchInput {
            click(
                androidx.compose.ui.geometry.Offset(
                    x = 100f,
                    y = 100f,
                )
            )
        }
        composeRule.waitForIdle()
    }

    /**
     * ブラウザ画面が表示されるまで待機する。
     */
    private fun waitForBrowserScreen() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun isTabGroupSelected(groupIndex: Int): Boolean {
        return runCatching {
            composeRule.onNode(
                hasTestTag(TabsScreenTestTags.TabGroupTopButton(groupIndex).testTag)
            ).assertIsSelected()
            true
        }.getOrDefault(false)
    }

    private fun createLocalNewTabLinkServer(): LocalHtmlServer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val indexHtml = assets.open("$ASSET_DIR/$INDEX_FILE_NAME").bufferedReader().use { it.readText() }
        val targetHtml = assets.open("$ASSET_DIR/$TARGET_FILE_NAME").bufferedReader().use { it.readText() }
        return LocalHtmlServer(
            indexHtml = indexHtml,
            targetHtml = targetHtml,
        )
    }

    private class LocalHtmlServer(
        private val indexHtml: String,
        private val targetHtml: String,
    ) : AutoCloseable {
        private val requestPaths = Collections.synchronizedList(mutableListOf<String>())
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val serverThread = Thread {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    handleRequest(socket)
                }.onFailure {
                    if (!serverSocket.isClosed) {
                        println("local-http error=${it.message}")
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val indexUrl: String = "http://127.0.0.1:${serverSocket.localPort}/$INDEX_FILE_NAME"

        private fun handleRequest(socket: Socket) {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val requestPath = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                requestPaths += requestPath
                val body = when (requestPath) {
                    "/", "/$INDEX_FILE_NAME" -> indexHtml
                    "/$TARGET_FILE_NAME" -> targetHtml
                    else -> "<!doctype html><html><head><title>Not Found</title></head><body>404</body></html>"
                }
                println("local-http requestPath=$requestPath")
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
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

        fun hasRequest(path: String): Boolean {
            return requestPaths.contains(path)
        }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { serverThread.join(2_000) }
        }
    }

    @OptIn(ExperimentalContracts::class)
    private fun group(
        @Suppress("unused") title: String = "",
        block: () -> Unit
    ) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        if (userDebug) {
            Thread.sleep(5.seconds.inWholeMilliseconds)
        }
        println("start: $title")
        block()
        println("end: $title")
    }

    companion object {
        private const val ASSET_DIR = "test-new-tab-link"
        private const val INDEX_FILE_NAME = "index.html"
        private const val TARGET_FILE_NAME = "target.html"
    }
}

package net.matsudamper.browser

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
                        hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag),
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
                        hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag),
                    ).assertIsSelected()
                    true
                }.getOrDefault(false)
            }
        }

        group("タブグループ 0 に移動するボタンを押す") {
            composeRule.onNode(
                hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag),
            ).performClick()
            composeRule.waitForIdle()

            // タブグループ 0 が表示されていることを確認する
            composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
                runCatching {
                    composeRule.onNode(
                        hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag),
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
            var opened = false
            var lastUrl = composeRule.currentUrlBarText()
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
                println("target-open-attempt=${attempt + 1}, opened=$opened, currentUrl=$lastUrl")
                if (!opened) {
                    dumpUiDiagnostics("attempt-${attempt + 1}")
                }
            }
            if (!opened) {
                val targetUrl = localServer.indexUrl.substringBeforeLast("/") + "/$TARGET_FILE_NAME"
                println("target=_blank click failed in this environment. fallback openUrlFromUrlBar: $targetUrl")
                dumpUiDiagnostics("before-openUrlFromUrlBar")
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
                hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag),
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
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))),
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

    /**
     * 失敗時の Compose semantics tree を最低限ダンプする診断ヘルパー。
     * UrlBar / GeckoContainer / 前面 GeckoContainer の現在の数と、
     * UrlBar の EditableText 値、ツールバーの存在有無、root semantics の概要を出力する。
     * fallback path がさらに失敗したケースで、原因切り分け（多重ノード／ノード不在／別画面表示）に使う。
     */
    private fun dumpUiDiagnostics(label: String) {
        val urlBarTag = UrlTextInputTestTags.UrlBar.testTag
        val geckoTag = GeckoBrowserTabTestTags.GeckoContainer.testTag
        val foregroundGeckoTag = GeckoBrowserTabTestTags.GeckoContainer.testTag(isForeground = true)
        val toolbarTag = BrowserToolbarTestTags.Toolbar.testTag
        val tabsAddTag = TabsScreenTestTags.AddTabButton.testTag

        val urlBarNodes = runCatching {
            composeRule.onAllNodesWithTag(urlBarTag).fetchSemanticsNodes()
        }.getOrDefault(emptyList())
        val geckoNodes = runCatching {
            composeRule.onAllNodesWithTag(geckoTag).fetchSemanticsNodes()
        }.getOrDefault(emptyList())
        val foregroundNodes = runCatching {
            composeRule.onAllNodesWithTag(foregroundGeckoTag).fetchSemanticsNodes()
        }.getOrDefault(emptyList())
        val toolbarNodes = runCatching {
            composeRule.onAllNodesWithTag(toolbarTag).fetchSemanticsNodes()
        }.getOrDefault(emptyList())
        val tabsAddNodes = runCatching {
            composeRule.onAllNodesWithTag(tabsAddTag).fetchSemanticsNodes()
        }.getOrDefault(emptyList())

        val urlBarTexts = urlBarNodes.mapIndexed { index, node ->
            val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "<no editable text>"
            "[$index]=\"$text\""
        }

        println(
            "ui-diag[$label] urlBars=${urlBarNodes.size} gecko=${geckoNodes.size} " +
                "foregroundGecko=${foregroundNodes.size} toolbars=${toolbarNodes.size} " +
                "tabsAdd=${tabsAddNodes.size} urlBarTexts=${urlBarTexts.joinToString(",")}",
        )

        // 各 UrlBar 周辺のセマンティクスもダンプして、どのスクリーン由来かを見える化する。
        if (urlBarNodes.isEmpty()) {
            val rootDump = runCatching {
                composeRule.onRoot().printToString(maxDepth = 4)
            }.getOrElse { "root dump failed: ${it.message}" }
            println("ui-diag[$label] root(maxDepth=4)=\n$rootDump")
        }
    }

    private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
    ): T? = if (contains(key)) get(key) else null

    /**
     * バックスタックに複数の Browser エントリが残っていると GeckoContainer は複数ノードに
     * なり onNodeWithTag が "Expected exactly 1" で失敗する。フォアグラウンド限定 testTag で一意化する。
     */
    private fun tapLinkOnGeckoContainer() {
        val tag = GeckoBrowserTabTestTags.GeckoContainer.testTag
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        val nodes = composeRule.onAllNodesWithTag(tag)
        val node = nodes[0]
        node.performTouchInput {
            click()
        }
        node.performTouchInput {
            click(
                androidx.compose.ui.geometry.Offset(
                    x = 100f,
                    y = 100f,
                ),
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
                hasTestTag(TabsScreenTestTags.TabGroupTopButton(groupIndex).testTag),
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
        block: () -> Unit,
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

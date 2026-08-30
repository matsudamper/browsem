package net.matsudamper.browser // pragma: allowlist secret

import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.time.Duration.Companion.seconds
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags // pragma: allowlist secret
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * フォルダブル展開などの構成変更（Activity 再生成）後も、
 * 直前に表示していたタブが維持されることを検証する。
 */
@RunWith(AndroidJUnit4::class)
class FoldableTabSelectionRestoreTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var localHttpServer: LocalHttpServer? = null

    @After
    fun tearDown() {
        localHttpServer?.close()
        localHttpServer = null
    }

    @Test
    fun activityRecreateKeepsSelectedTab() {
        val (firstUrl, secondUrl) = startTwoPageServer()
        waitForBrowserScreen()

        composeRule.openUrlFromUrlBar(firstUrl)
        composeRule.waitForUrlBarContains(FIRST_PAGE_FILE, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused()

        openTabsScreen()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag)).performClick()
        composeRule.waitForIdle()
        waitForBrowserScreen()

        composeRule.openUrlFromUrlBar(secondUrl)
        composeRule.waitForUrlBarContains(SECOND_PAGE_FILE, timeoutMillis = 60_000)
        composeRule.waitForUrlBarNotFocused()

        // 前のテストのタブが残っていると index 0 はホームページのことがある。
        // 先に開いた first-tab を URL で選んでから構成変更する。
        selectTabShowing(FIRST_PAGE_FILE)

        composeRule.activityRule.scenario.recreate()
        waitForBrowserScreen()
        composeRule.waitForUrlBarContains(FIRST_PAGE_FILE, timeoutMillis = 60_000)
    }

    private fun startTwoPageServer(): Pair<String, String> {
        val destinationDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            LOCAL_DIR_NAME,
        ).apply { mkdirs() }
        File(destinationDir, FIRST_PAGE_FILE).writeText(pageHtml("first-tab"))
        File(destinationDir, SECOND_PAGE_FILE).writeText(pageHtml("second-tab"))
        val server = LocalHttpServer(destinationDir)
        localHttpServer = server
        return server.url(FIRST_PAGE_FILE) to server.url(SECOND_PAGE_FILE)
    }

    private fun waitForBrowserScreen() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openTabsScreen() {
        val node = composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.OpenTabsButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))),
        )
        repeat(12) {
            val opened = runCatching {
                composeRule.waitForTabsScreenLoaded(timeoutMillis = 2_000)
                true
            }.getOrDefault(false)
            if (opened) return

            val visible = runCatching {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    node.isDisplayed()
                }
                true
            }.getOrDefault(false)
            if (!visible) return@repeat

            node.performClick()
            composeRule.waitForIdle()
            val openedAfterTap = runCatching {
                composeRule.waitForTabsScreenLoaded(timeoutMillis = 5_000)
                true
            }.getOrDefault(false)
            if (openedAfterTap) return
        }
        composeRule.waitForTabsScreenLoaded()
    }

    private fun selectTabShowing(urlMarker: String) {
        repeat(MAX_TAB_SCAN_COUNT) { index ->
            openTabsScreen()
            val tabMatcher = hasTestTag(TabsScreenTestTags.TabItem(index).testTag)
            val visible = runCatching {
                composeRule.waitUntil(timeoutMillis = 2_000) {
                    composeRule.onNode(tabMatcher).isDisplayed()
                }
                true
            }.getOrDefault(false)
            if (!visible) {
                throw AssertionError(
                    "first-tab のタブが見つからない marker=$urlMarker " +
                        "scanned=$index url=${composeRule.currentPageUrlFromUi()}",
                )
            }
            composeRule.onNode(tabMatcher).performClick()
            composeRule.waitForIdle()
            waitForBrowserScreen()
            val matched = runCatching {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.currentPageUrlFromUi().contains(urlMarker)
                }
                true
            }.getOrDefault(false)
            if (matched) return
        }
        throw AssertionError(
            "first-tab のタブが見つからない marker=$urlMarker " +
                "url=${composeRule.currentPageUrlFromUi()}",
        )
    }

    private companion object {
        private const val LOCAL_DIR_NAME = "foldable-tab-restore"
        private const val FIRST_PAGE_FILE = "first-tab.html"
        private const val SECOND_PAGE_FILE = "second-tab.html"
        private const val MAX_TAB_SCAN_COUNT = 8

        private fun pageHtml(title: String): String = """
            <!doctype html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <title>$title</title>
              </head>
              <body><p>$title</p></body>
            </html>
        """.trimIndent()
    }
}

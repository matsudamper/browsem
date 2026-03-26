package net.matsudamper.browser

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.screen.tab.TabsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * target="_blank" で開いたタブの URL バースワイプ前後タブが、
 * オープナーと同じグループ内のタブになることを検証する。
 *
 * バグ: タブグループ 0 のタブから target="_blank" で開いた子タブが
 * グループに割り当てられず、orderedTabs の末尾（ungrouped）に配置される。
 * そのため URL バーの左スワイプがグループ 1 のタブに遷移してしまう。
 */
@RunWith(AndroidJUnit4::class)
class TabGroupSwipeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun targetBlankTabShouldHavePrevTabFromSameGroup() {
        val browserSessionController = waitForBrowserSessionController()
        waitForActiveTab(browserSessionController)

        // タブリスト画面を開く
        openTabsScreen()
        waitForTabsScreen()

        // 新しいタブグループを作るボタンを押す
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
            .performClick()
        composeRule.waitForIdle()

        // タブグループ 1 が表示されていることを確認する
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNode(
                    hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
                ).assertIsSelected()
                true
            }.getOrDefault(false)
        }

        // タブの新規追加ボタンを押す
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag))
            .performClick()
        composeRule.waitForIdle()

        // タブ画面が開かれていることを確認する
        waitForBrowserScreen()

        // タブリスト画面を開く
        openTabsScreen()
        waitForTabsScreen()

        // タブグループ 1 が表示されていることを確認する
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNode(
                    hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
                ).assertIsSelected()
                true
            }.getOrDefault(false)
        }

        // タブグループ 0 に移動するボタンを押す
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
        ).performClick()
        composeRule.waitForIdle()

        // タブグループ 0 が表示されていることを確認する
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNode(
                    hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
                ).assertIsSelected()
                true
            }.getOrDefault(false)
        }

        // タブを新規追加するボタンを押す
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag))
            .performClick()
        composeRule.waitForIdle()

        // タブ画面が開かれていることを確認する
        waitForBrowserScreen()

        // ローカル HTML を読み込む
        val activeTab = getCurrentActiveTab(browserSessionController)
        val localPageUri = prepareLocalNewTabLinkPageUri()
        composeRule.runOnIdle {
            activeTab.session.loadUri(localPageUri)
        }
        waitForActiveTabUrl(timeoutMillis = 60_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("file:") && currentUrl.contains(INDEX_FILE_NAME)
        }

        // グループ 0 に追加したタブの ID を記録
        val openerTabId = activeTab.tabId
        val tabCountBefore = browserSessionController.tabs.size

        // リンクをクリックする（target="_blank"）
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:void(document.getElementById('newTabLink').click())"
            )
        }

        // 新しいタブが作成されるまで待つ
        composeRule.waitUntil(timeoutMillis = 30_000) {
            var result = false
            composeRule.runOnIdle {
                result = browserSessionController.tabs.size > tabCountBefore
            }
            result
        }

        // 新しいタブに遷移するまで待つ
        composeRule.waitUntil(timeoutMillis = 10_000) {
            var result = false
            composeRule.runOnIdle {
                result = browserSessionController.selectedTabId != openerTabId
            }
            result
        }

        // 割り当て処理が非同期で走るので少し待つ
        Thread.sleep(3_000)
        composeRule.waitForIdle()

        // タブ一覧画面を開いて、新しいタブがグループ 0 に表示されることを確認する
        openTabsScreen()
        waitForTabsScreen()

        // グループ 0 を選択する
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
        ).performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNode(
                    hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
                ).assertIsSelected()
                true
            }.getOrDefault(false)
        }

        // target="_blank" で開いたタブ ("Target Page") がグループ 0 に表示されていることを確認する
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Target Page")
                .fetchSemanticsNodes().isNotEmpty()
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
        composeRule.waitUntil(timeoutMillis = 20_000) {
            node.isDisplayed()
        }
        node.performClick()
        composeRule.waitForIdle()
    }

    /**
     * タブ一覧画面が表示されるまで待機する。
     */
    private fun waitForTabsScreen() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("名前変更").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * ブラウザ画面が表示されるまで待機する。
     */
    private fun waitForBrowserScreen() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 現在選択中のタブを取得する。
     */
    private fun getCurrentActiveTab(
        browserSessionController: BrowserSessionController,
    ): BrowserTab {
        var activeTab: BrowserTab? = null
        composeRule.runOnIdle {
            val selectedId = browserSessionController.selectedTabId
            activeTab = browserSessionController.tabs.firstOrNull { it.tabId == selectedId }
                ?: browserSessionController.tabs.lastOrNull()
        }
        return requireNotNull(activeTab)
    }

    /**
     * アクティブタブの URL が条件を満たすまで待機する。
     */
    private fun waitForActiveTabUrl(
        timeoutMillis: Long,
        activeTab: BrowserTab,
        predicate: (String) -> Boolean,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            var matched = false
            composeRule.runOnIdle {
                matched = predicate(activeTab.currentUrl)
            }
            matched
        }
    }

    /**
     * ローカルHTMLをキャッシュへ展開し、file URI を返す。
     */
    private fun prepareLocalNewTabLinkPageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        // index.html と target.html の両方をコピー
        listOf(INDEX_FILE_NAME, TARGET_FILE_NAME).forEach { fileName ->
            val destination = File(destinationDir, fileName)
            assetManager.open("$ASSET_DIR/$fileName").use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return File(destinationDir, INDEX_FILE_NAME).toURI().toString()
    }

    /**
     * BrowserSessionController が利用可能になるまで待機して取得する。
     */
    private fun waitForBrowserSessionController(): BrowserSessionController {
        var controller: BrowserSessionController? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var resolved = false
            composeRule.runOnIdle {
                resolved = runCatching {
                    controller = getBrowserViewModel().browserSessionController
                }.isSuccess
            }
            resolved
        }
        return requireNotNull(controller)
    }

    /**
     * 現在操作対象の BrowserTab が確定するまで待機する。
     */
    private fun waitForActiveTab(browserSessionController: BrowserSessionController) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var found = false
            composeRule.runOnIdle {
                val activeTab = browserSessionController.tabs.firstOrNull { it.session.isOpen }
                    ?: browserSessionController.tabs.lastOrNull()
                found = activeTab != null
            }
            found
        }
    }

    /**
     * Activity から BrowserViewModel を取得する。
     */
    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
    }

    companion object {
        private const val ASSET_DIR = "test-new-tab-link"
        private const val DIR_NAME = "test-new-tab-link"
        private const val INDEX_FILE_NAME = "index.html"
        private const val TARGET_FILE_NAME = "target.html"
    }
}

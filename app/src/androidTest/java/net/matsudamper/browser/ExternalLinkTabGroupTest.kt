package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.matsudamper.browser.screen.tab.TabsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * 外部リンクで開いたタブがデフォルトグループで表示されることを確認する
 **/
@RunWith(AndroidJUnit4::class)
class ExternalLinkTabGroupTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun externalLinkTabShouldShowCorrectGroupInTabList() {
        val browserSessionController = waitForBrowserSessionController()
        waitForActiveTab(browserSessionController)

        // タブボタンをタップしてタブ一覧画面を開く
        tapTabButton()
        waitForTabsScreen()

        // グループを追加する
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag)).performClick()
        composeRule.waitForIdle()

        // 追加したグループ1のデフォルトスイッチを ON にする
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(
                hasTestTag(TabsScreenTestTags.DefaultGroupSwitch(1).testTag)
            ).isDisplayed()
        }
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.DefaultGroupSwitch(1).testTag)
        ).performClick()
        composeRule.waitForIdle()

        // 最初のグループ0に戻る
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)).performClick()
        composeRule.waitForIdle()

        // 新規タブボタン（FAB）をタップしてブラウザに遷移する
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag)).performClick()
        composeRule.waitForIdle()

        // ブラウザ画面が表示されるまで待つ
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 再びタブボタンをタップしてタブ一覧画面を開く
        tapTabButton()
        waitForTabsScreen()

        // 外部からリンクを表示する
        ActivityScenario.launch<MainActivity>(
            Intent(Intent.ACTION_VIEW).also { intent ->
                intent.data = "https://www.example.com".toUri()
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.setClass(composeRule.activity, MainActivity::class.java)
            }
        )

        // ページが開かれている事を確認する
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onNode(hasTestTag(BrowserToolbarTestTags.Url("https://www.example.com").testTag)).isDisplayed()
        }

        // 再びタブボタンをタップしてタブ一覧画面を開く
        tapTabButton()
        waitForTabsScreen()

        // 表示中のグループのデフォルトスイッチが ON なグループ1な事を確認する
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.Page(1).testTag)).isDisplayed()
        }
    }

    /**
     * ツールバーのタブボタンをタップする。
     * hasParent でツールバー内のボタンに絞ることで GeckoView 層への誤タップを防ぐ。
     */
    private fun tapTabButton() {
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
            composeRule.onAllNodesWithText("名前変更")
                .fetchSemanticsNodes().isNotEmpty()
        }
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
     * 現在操作対象の BrowserTab が確定するまで待機して取得する。
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
}

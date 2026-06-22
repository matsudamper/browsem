package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
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
        ensureTabsScreen()

        // グループを追加する
        // 他の待機と同じ10秒に揃える（2秒では低速なエミュレータで間に合わないことがある）
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag)).performClick()
        composeRule.waitForIdle()

        // 追加したグループ1を選択する
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)).performClick()
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
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 再びタブボタンをタップしてタブ一覧画面を開く
        tapTabButton()
        waitForTabsScreen()

        // 外部からリンクを表示する
        composeRule.openUrlViaViewIntent("https://www.example.com".toUri().toString())

        // ページが開かれている事を確認する
        composeRule.waitForUrlBarContains("www.example.com", timeoutMillis = 30_000)

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
        val alreadyOpened = runCatching {
            composeRule.waitForTabsScreenLoaded(timeoutMillis = 2_000)
            true
        }.getOrDefault(false)
        if (alreadyOpened) return

        val node = composeRule.onNode(
            hasTestTag(BrowserToolbarTestTags.OpenTabsButton.testTag)
                .and(hasParent(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)))
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

    private fun ensureTabsScreen() {
        tapTabButton()
        waitForTabsScreen()
    }

    /**
     * タブ一覧画面が表示されるまで待機する。
     */
    private fun waitForTabsScreen() {
        composeRule.waitForTabsScreenLoaded()
    }

}

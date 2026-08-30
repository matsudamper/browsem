package net.matsudamper.browser

import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.seconds
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 外部から開いたタブがデフォルトグループの末尾に追加されることを検証する。
 * 選択中のタブが末尾でない場合に、選択タブの直後ではなくグループ末尾に配置されること。
 */
@RunWith(AndroidJUnit4::class)
class ExternalTabInsertionPositionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun externalTabShouldBeAppendedAtEndOfDefaultGroup() {
        // ブラウザ画面が表示されるまで待つ（初期タブ A がインデックス 0）
        waitForBrowserScreen()

        // タブ一覧画面を開く
        openTabsScreen()

        // 新しいタブを追加する（タブ B がインデックス 1 に追加され、選択される）
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag)).performClick()
        composeRule.waitForIdle()

        // ブラウザ画面が表示されるまで待つ
        waitForBrowserScreen()

        // タブ一覧画面を開く
        openTabsScreen()

        // タブ A（インデックス 0）をタップして選択する
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.TabItem(0).testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.TabItem(0).testTag)).performClick()
        composeRule.waitForIdle()

        // ブラウザ画面が表示されるまで待つ
        waitForBrowserScreen()

        // 外部からリンクを開く
        composeRule.openUrlViaViewIntent("https://www.example.com".toUri().toString())

        // ページが開かれていることを確認する
        composeRule.waitForUrlBarContains("www.example.com", timeoutMillis = 30_000)

        // タブ一覧画面を開く
        openTabsScreen()

        // 外部タブがインデックス 2（末尾）に存在することを確認する
        // インデックス 1（タブ A の直後）ではなくインデックス 2 にあることが修正のポイント
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.TabItem(2).testTag))
                .isDisplayed()
        }

        // インデックス 2 のタブが選択状態（外部タブ）であることを確認する
        // 外部タブは開いた直後に選択されるため、選択状態で末尾にあることが期待値
        composeRule.onNode(hasTestTag(TabsScreenTestTags.TabItem(2).testTag)).performClick()
        composeRule.waitForIdle()
        waitForBrowserScreen()
        composeRule.waitForUrlBarContains("www.example.com", timeoutMillis = 10_000)
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
}

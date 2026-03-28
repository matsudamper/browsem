package net.matsudamper.browser

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.matsudamper.browser.screen.tab.TabsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class TabGroupNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()


    /**
     * タブ画面を閉じた後に再度タブ画面を開くと前に開いたタブグループと同じタブグループが表示されないかを確認する
     */
    @Test
    fun activeGroupShouldRestoreToSelectedTabGroupAfterNavigation() = runTest {
        waitForBrowserScreen()

        // タブ一覧画面を開いて group1 を初期化する
        openTabsScreen()
        waitForTabsScreen()

        // タブをグループを追加する
        composeRule.waitUntil(timeoutMillis = 2.seconds.inWholeMilliseconds) {
            composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag))
                .isDisplayed()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabGroupButton.testTag)).performClick()
        composeRule.waitForIdle()

        // タブグループ1が表示されている
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
        ).assertIsSelected()

        // タブを追加する: グループ1で追加したから、グループ1に追加されるはず
        composeRule.onNode(hasTestTag(TabsScreenTestTags.AddTabButton.testTag)).performClick()
        composeRule.waitForIdle()

        // タブ画面を開く
        openTabsScreen()
        waitForTabsScreen()

        // タブグループ1が表示されている: タブグループ1のタブが表示されていたのだから当然
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
        ).assertIsSelected()

        // タブグループ0を表示する
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.TabGroupTopButton(0).testTag)
        ).performClick()

        // バックでブラウザへ戻る
        pressSystemBack()
        waitForBrowserScreen()

        // タブ画面を開く
        openTabsScreen()
        waitForTabsScreen()

        // タブグループ1が表示されている: タブグループ1のタブが表示されていたのだから当然
        composeRule.onNode(
            hasTestTag(TabsScreenTestTags.TabGroupTopButton(1).testTag)
        ).assertIsSelected()
    }

    /**
     * タブ一覧ボタンをタップしてタブ一覧画面へ遷移する。
     * performClick() を使い Compose セマンティクスツリー経由で操作することで、
     * GeckoView の AndroidView 層に邪魔されずにクリックできる。
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
        composeRule.waitForTabsScreenLoaded()
    }

    /**
     * システムの戻る操作を1回発行する。
     * GmdSmokeTest と同じパターン。
     */
    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    /**
     * ブラウザ画面（ツールバー）が表示されるまで待機する。
     */
    private fun waitForBrowserScreen() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

}

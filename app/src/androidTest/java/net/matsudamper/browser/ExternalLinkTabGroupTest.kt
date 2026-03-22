package net.matsudamper.browser

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.matsudamper.browser.screen.tab.TabsScreenTestTags
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 外部リンクで開いたタブが属するグループがタブ一覧画面で正しく表示されることを検証する。
 *
 * バグ: タブ一覧画面でグループ追加 → デフォルト設定 → 新規タブ作成後、
 * 再度タブ一覧を開くと activeGroupIndex が更新されず最初のグループが表示される。
 *
 * テストフロー:
 * 1. タブボタンをタップしてタブ一覧画面を開く
 * 2. グループを追加する
 * 3. 追加したグループのデフォルトスイッチを ON にする
 * 4. 新規タブボタン（FAB）をタップしてブラウザに遷移する
 * 5. 再びタブボタンをタップしてタブ一覧画面を開く
 * 6. 表示中のグループのデフォルトスイッチが ON であることを確認する
 */
@RunWith(AndroidJUnit4::class)
class ExternalLinkTabGroupTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun externalLinkTabShouldShowCorrectGroupInTabList() {
        val browserSessionController = waitForBrowserSessionController()
        waitForActiveTab(browserSessionController)

        // 1. タブボタンをタップしてタブ一覧画面を開く
        tapTabButton()
        waitForTabsScreen()

        // 2. グループを追加する
        composeRule.onAllNodesWithContentDescription("グループを追加")[0].performClick()
        composeRule.waitForIdle()

        // 3. 追加したグループのデフォルトスイッチを ON にする
        // グループ追加後、自動的に新しいグループページに遷移するため、
        // 表示中のページのデフォルトスイッチをタップする。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(isToggleable())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitForIdle()

        // 4. 最初のグループに戻る（グループタブバーの最初のグループをタップ）
        // グループ追加後は新しいグループ（index=1）にいるため、index=0 のグループに移動する。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag(TabsScreenTestTags.tabGroupTestTag(0)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasTestTag(TabsScreenTestTags.tabGroupTestTag(0))).performClick()
        composeRule.waitForIdle()

        // 5. 新規タブボタン（FAB）をタップしてブラウザに遷移する
        composeRule.onAllNodesWithContentDescription("新規タブ")[0].performClick()
        composeRule.waitForIdle()

        // ブラウザ画面が表示されるまで待つ
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(TEST_TAG_TOOLBAR))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 5. 再びタブボタンをタップしてタブ一覧画面を開く
        tapTabButton()
        waitForTabsScreen()

        // 6. 表示中のグループのデフォルトスイッチが ON であることを確認する
        composeRule.onAllNodes(isToggleable())[0].assertIsOn()
    }

    /**
     * ツールバーのタブボタンをタップする。
     * TabPreviewPage にもボタンがあるため、画面内に表示されているものを選ぶ。
     */
    private fun tapTabButton() {
        val screenWidth = composeRule.activity.resources.displayMetrics.widthPixels
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(TEST_TAG_OPEN_TABS))
                .fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0 && it.boundsInRoot.right <= screenWidth }
        }
        val visibleNode = composeRule.onAllNodes(hasTestTag(TEST_TAG_OPEN_TABS))
            .fetchSemanticsNodes()
            .first { it.boundsInRoot.width > 0 && it.boundsInRoot.right <= screenWidth }
        val bounds = visibleNode.boundsInRoot
        injectTap((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
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
     * UiAutomation 経由でタップイベントを注入する。
     */
    private fun injectTap(x: Float, y: Float) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
        uiAutomation.injectInputEvent(down, true)
        uiAutomation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
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

    /**
     * デバッグ時にユーザーに操作させて確認したい場合に差し込む
     */
    private fun waitDebugUserInteractionInfinity() {
        composeRule.waitUntil(timeoutMillis = Long.MAX_VALUE) { false }
    }
}

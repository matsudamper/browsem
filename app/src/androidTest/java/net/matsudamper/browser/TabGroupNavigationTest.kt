package net.matsudamper.browser

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.screen.tab.TabsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * タブグループのナビゲーション後に activeGroupIndex が正しく復元されることを検証する。
 *
 * バグ: TabsScreenViewModel の初期化時に observeGroups() が observeTabGroupAssignments() より
 * 先に結果を返した場合、assignments が空のまま activeGroupIndex が復元されてしまい、
 * 選択中タブが属する group2 ではなく group1 (index=0) が表示される。
 *
 * テストフロー:
 * 1. タブ一覧を開いて group1 を初期化する
 * 2. バックでブラウザへ戻る
 * 3. Koin 経由で group2 を追加し、選択中タブを group2 に割り当てる
 * 4. 2つめのタブグループのタブを選択する（タブ一覧を開く）
 * 5. 1つめのタブグループを開く
 * 6. バックボタンでタブに戻る
 * 7. タブグループを開く
 * 8. タブグループの2つ目が選択されていることを確認する
 */
@RunWith(AndroidJUnit4::class)
class TabGroupNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeGroupShouldRestoreToSelectedTabGroupAfterNavigation() {
        val browserSessionController = waitForBrowserSessionController()
        waitForActiveTab(browserSessionController)

        // 1. タブ一覧画面を開いて group1 を初期化する
        tapTabButton()
        waitForTabsScreen()

        // 2. バックでブラウザへ戻る（TabsScreenViewModel が group1 を作成・初期タブを割り当てる）
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        uiDevice.pressBack()
        composeRule.waitForIdle()

        // ブラウザ画面が表示されるまで待つ
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(TEST_TAG_TOOLBAR)).fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Koin 経由で group2 を追加し、選択中タブを group2 に割り当てる
        val initialTabId = requireNotNull(
            browserSessionController.selectedTabId
                ?: browserSessionController.tabs.firstOrNull()?.tabId,
        )
        val tabGroupRepository = GlobalContext.get().get<TabGroupRepository>()
        runBlocking {
            val group2Id = tabGroupRepository.addGroup("グループ 2", 1)
            tabGroupRepository.assignTabToGroup(initialTabId, group2Id)
        }

        // 4. 2つめのタブグループのタブを選択する（タブ一覧を開いて group2 に初期タブが属することを確認）
        tapTabButton()
        waitForTabsScreen()

        // group2 のタブバーが表示されるまで待つ
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag(TabsScreenTestTags.tabGroupTestTag(1)))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 5. 1つめのタブグループを開く
        composeRule.onNode(hasTestTag(TabsScreenTestTags.tabGroupTestTag(0))).performClick()
        composeRule.waitForIdle()

        // 6. バックボタンでタブに戻る
        uiDevice.pressBack()
        composeRule.waitForIdle()

        // ブラウザ画面が表示されるまで待つ
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(TEST_TAG_TOOLBAR)).fetchSemanticsNodes().isNotEmpty()
        }

        // 7. タブグループを開く
        tapTabButton()
        waitForTabsScreen()

        // 8. タブグループの2つ目が選択されていることを確認する
        // 選択中タブは group2 に属しているため、activeGroupIndex = 1 となるはず
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(
                hasTestTag(TabsScreenTestTags.tabGroupTestTag(1)).and(isSelected()),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * ツールバーのタブボタンをタップする。
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
            composeRule.onAllNodesWithText("名前変更").fetchSemanticsNodes().isNotEmpty()
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
}

package net.matsudamper.browser

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * 2. ブラウザへ戻る
 * 3. group2 を追加し、選択中タブを group2 に割り当てる
 * 4. タブ一覧を再度開く
 * 5. group1 を選択する
 * 6. ブラウザへ戻る
 * 7. タブ一覧を再度開く
 * 8. group2 が選択されていることを確認する（選択中タブが group2 に属するため）
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
        openTabsScreen()
        waitForTabsScreen()

        // 2. バックでブラウザへ戻る
        pressSystemBack()
        waitForBrowserScreen()

        // 3. group2 を追加し、選択中タブを group2 に割り当てる
        //    （テストデータのシードは GmdSmokeTest と同じパターンで直接リポジトリを使用する）
        val initialTabId = requireNotNull(
            browserSessionController.selectedTabId
                ?: browserSessionController.tabs.firstOrNull()?.tabId,
        )
        val tabGroupRepository = GlobalContext.get().get<TabGroupRepository>()
        composeRule.runOnIdle {
            runBlocking {
                val group2Id = tabGroupRepository.addGroup("グループ 2", 1)
                tabGroupRepository.assignTabToGroup(initialTabId, group2Id)
            }
        }

        // 4. タブ一覧を再度開く（2回目）
        openTabsScreen()
        waitForTabsScreen()

        // group2 タブが表示されるまで待つ
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag(TabsScreenTestTags.tabGroupTestTag(1)))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 5. group1 を選択する
        composeRule.onNode(hasTestTag(TabsScreenTestTags.tabGroupTestTag(0))).performClick()
        composeRule.waitForIdle()

        // 6. バックでブラウザへ戻る
        pressSystemBack()
        waitForBrowserScreen()

        // 7. タブ一覧を再度開く（3回目）
        openTabsScreen()
        waitForTabsScreen()

        // 8. 選択中タブは group2 に属しているため activeGroupIndex = 1 になるはず
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(
                hasTestTag(TabsScreenTestTags.tabGroupTestTag(1)).and(isSelected()),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * タブ一覧ボタンをタップしてタブ一覧画面へ遷移する。
     * performClick() を使い Compose セマンティクスツリー経由で操作することで、
     * GeckoView の AndroidView 層に邪魔されずにクリックできる。
     */
    private fun openTabsScreen() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag(TEST_TAG_OPEN_TABS))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasTestTag(TEST_TAG_OPEN_TABS)).performClick()
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
            composeRule.onAllNodes(hasTestTag(TEST_TAG_TOOLBAR)).fetchSemanticsNodes().isNotEmpty()
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

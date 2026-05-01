package net.matsudamper.browser

import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ページ内検索（FindInPage）機能の動作を確認するインストゥルメンテーションテスト。
 *
 * - 検索バーを開いたときに入力フィールドがフォーカスを取得すること
 * - 検索バーが表示されている状態でバックを押すと検索が閉じること（ページは戻らないこと）
 */
@RunWith(AndroidJUnit4::class)
class FindInPageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * メニューからページ内検索を開いたとき、検索入力フィールドがフォーカスを取得することを確認する。
     */
    @Test
    fun openingFindInPageFocusesSearchInput() {
        ensureBrowserScreen()

        openFindInPage()

        waitForFindInPageVisible()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            isSearchInputFocused()
        }
        assertTrue("検索入力フィールドがフォーカスを取得していない", isSearchInputFocused())
    }

    /**
     * ページ内検索が表示されている状態でバックを押したとき、
     * 検索バーが閉じてページナビゲーション（戻る）が発生しないことを確認する。
     */
    @Test
    fun backButtonClosesFindInPageWithoutNavigatingBack() {
        ensureBrowserScreen()

        openFindInPage()
        waitForFindInPageVisible()

        val urlBeforeBack = composeRule.currentUrlBarText()

        pressSystemBack()

        waitForFindInPageHidden()

        val urlAfterBack = composeRule.currentUrlBarText()
        assertTrue("検索を閉じた後にURLが変わった（ページが戻った）", urlBeforeBack == urlAfterBack)
        assertTrue(
            "アプリが終了している",
            !composeRule.activity.isFinishing,
        )
    }

    // ---- ヘルパー ----

    private fun ensureBrowserScreen() {
        val browserReady = runCatching {
            waitForToolbarReady()
            true
        }.getOrDefault(false)
        if (browserReady) return

        val tabsReady = runCatching {
            composeRule.waitForTabsScreenLoaded(timeoutMillis = 10_000)
            true
        }.getOrDefault(false)
        if (tabsReady) {
            composeRule.onNodeWithTag(TabsScreenTestTags.AddTabButton.testTag).performClick()
            composeRule.waitForIdle()
        }
        waitForToolbarReady()
    }

    private fun waitForToolbarReady() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(BrowserToolbarTestTags.Toolbar.testTag).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    private fun openFindInPage() {
        composeRule.onNodeWithTag(BrowserToolbarTestTags.MenuButton.testTag).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.FindInPageButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrowserToolbarMenuTestTags.FindInPageButton.testTag).performClick()
        composeRule.waitForIdle()
    }

    private fun waitForFindInPageVisible() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(FindInPageBarTestTags.SearchInput.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForFindInPageHidden() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(FindInPageBarTestTags.SearchInput.testTag)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun isSearchInputFocused(): Boolean {
        return runCatching {
            composeRule.onNodeWithTag(FindInPageBarTestTags.SearchInput.testTag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        }.getOrDefault(false)
    }

    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }
}

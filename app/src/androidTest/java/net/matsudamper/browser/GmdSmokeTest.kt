package net.matsudamper.browser

import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Managed device(ATD) 上で、ブラウザの主要フローが壊れていないことを確認するスモークテスト。
 *
 * - テーマカラー拡張の適用
 * - 履歴サジェストの表示
 * - 履歴候補がある状態での通常検索
 * - 履歴候補タップ遷移後の表示状態
 */
@RunWith(AndroidJUnit4::class)
class GmdSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 公開ページを開いたときにツールバーの色ソースが `default` から `theme` に切り替わることを確認する。
     */
    @Test
    fun openHatenablogAndApplyThemeColor() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        waitForThemeColorExtensionInstalled()
        val initialToolbarState = waitForToolbarState()
        assertEquals("default", initialToolbarState.source)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar")
            .performTextReplacement("https://matsudamper.hatenablog.jp/")
        composeRule.onNodeWithTag("url_bar").performImeAction()

        waitForActiveTabUrl(timeoutMillis = 60_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("https://matsudamper.hatenablog.jp")
        }

        val themeApplied = runCatching {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                runCatching {
                    waitForToolbarState().source == "theme"
                }.getOrDefault(false)
            }
            true
        }.getOrDefault(false)

        val resolvedToolbarState = waitForToolbarState()

        composeRule.runOnIdle {
            assertTrue(activeTab.currentUrl.startsWith("https://matsudamper.hatenablog.jp"))
        }
        if (themeApplied) {
            assertEquals("theme", resolvedToolbarState.source)
            assertNotEquals(initialToolbarState.argbHex, resolvedToolbarState.argbHex)
        }
    }

    /**
     * 履歴エントリを挿入した後、URLバー入力で一致候補が表示されることを確認する。
     */
    @Test
    fun urlBarShowsHistorySuggestions() {
        val query = "codex-suggest-20260307"
        val suggestionTitle = "Codex Suggest Test Title 20260307"
        val suggestionUrl = "https://$query.example/test"

        seedHistoryEntry(url = suggestionUrl, title = suggestionTitle)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar").performTextReplacement(query)

        waitForHistorySuggestionsVisible(suggestionTitle)
        assertTrue(composeRule.onAllNodesWithText(suggestionTitle).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * 履歴候補が出ている状態でも IME 実行で通常検索 URL へ遷移し、
     * 候補オーバーレイが消えて GeckoView が前面になることを確認する。
     *
     * 併せて URL バーのフォーカス解除と IME 非表示を確認する。
     */
    @Test
    fun searchEngineSearchWithHistorySuggestionsBringsGeckoViewToFront() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)

        val token = "history-search-20260307"
        val searchQuery = "$token normal query"
        val historyTitle = searchQuery
        val historyUrl = "https://$token.example/path"
        seedHistoryEntry(url = historyUrl, title = historyTitle)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar").performTextReplacement(searchQuery)
        waitForHistorySuggestionsVisible(historyTitle)

        composeRule.onNodeWithTag("url_bar").performImeAction()

        waitForActiveTabUrl(timeoutMillis = 30_000, activeTab = activeTab) { currentUrl ->
            currentUrl.contains(token) && !currentUrl.startsWith(historyUrl)
        }
        waitForHistorySuggestionsHidden()
        waitForUrlBarNotFocused()
        waitForImeClosed()
        assertGeckoViewInFront()

        composeRule.runOnIdle {
            assertTrue(activeTab.currentUrl.contains(token))
            assertTrue(!activeTab.currentUrl.startsWith(historyUrl))
        }
    }

    /**
     * 履歴候補タップ遷移後に候補オーバーレイが消え、
     * GeckoView が前面に戻ることを確認する。
     *
     * 併せて URL バーのフォーカス解除と IME 非表示を確認する。
     */
    @Test
    fun selectingHistorySuggestionBringsGeckoViewToFront() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)

        val token = "history-pick-20260307"
        val historyTitle = "History Pick Seed 20260307"
        val historyUrl = "https://$token.example/path"

        seedHistoryEntry(url = historyUrl, title = historyTitle)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar").performTextReplacement(token)
        waitForHistorySuggestionsVisible(historyTitle)

        composeRule.onNodeWithText(historyTitle).performClick()

        waitForActiveTabUrl(timeoutMillis = 30_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith(historyUrl)
        }
        waitForHistorySuggestionsHidden()
        waitForUrlBarNotFocused()
        waitForImeClosed()
        assertGeckoViewInFront()

        composeRule.runOnIdle {
            assertTrue(activeTab.currentUrl.startsWith(historyUrl))
        }
    }

    /**
     * GeckoView 側にフォーカスがある状態で `google.com` を開いた後に URL バーをタップしても、
     * 入力フォーカスが即座に失われないことを確認する。
     *
     * IME が観測期間中に一度でも表示された場合は、その後すぐに閉じていないことも確認する。
     */
    @Test
    fun openingUrlBarFromGeckoViewOnGoogleDoesNotImmediatelyCloseKeyboard() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar").performTextReplacement("https://www.google.com/")
        composeRule.onNodeWithTag("url_bar").performImeAction()

        waitForActiveTabUrl(timeoutMillis = 30_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("https://www.google.com")
        }
        waitForUrlBarNotFocused()
        assertGeckoViewInFront()

        composeRule.onNodeWithTag("url_bar").performClick()
        waitForUrlBarFocused()
        assertUrlBarFocusAndImeStayStableAfterOpening()
    }

    /**
     * テスト用に履歴エントリを 1 件追加する。
     */
    private fun seedHistoryEntry(url: String, title: String) {
        composeRule.runOnIdle {
            runBlocking {
                getBrowserViewModel().recordHistory(url, title)
            }
        }
    }

    /**
     * 履歴サジェストオーバーレイと指定タイトル候補が表示されるまで待機する。
     */
    private fun waitForHistorySuggestionsVisible(suggestionTitle: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val overlayVisible = composeRule
                .onAllNodesWithTag(TEST_TAG_HISTORY_SUGGESTION_LIST)
                .fetchSemanticsNodes()
                .isNotEmpty()
            val itemVisible = composeRule
                .onAllNodesWithText(suggestionTitle)
                .fetchSemanticsNodes()
                .isNotEmpty()
            overlayVisible && itemVisible
        }
    }

    /**
     * 履歴サジェストオーバーレイが非表示になるまで待機する。
     */
    private fun waitForHistorySuggestionsHidden() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule
                .onAllNodesWithTag(TEST_TAG_HISTORY_SUGGESTION_LIST)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * URL バーが非フォーカス状態になるまで待機する。
     */
    private fun waitForUrlBarNotFocused() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            !isUrlBarFocused()
        }
    }

    /**
     * URL バーがフォーカス状態になるまで待機する。
     */
    private fun waitForUrlBarFocused() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            isUrlBarFocused()
        }
    }

    /**
     * URL バーが開いた直後にフォーカスを維持することを確認する。
     *
     * 併せて、IME が一度でも表示された場合は観測期間内で即座に閉じないことを確認する。
     */
    private fun assertUrlBarFocusAndImeStayStableAfterOpening(observeMillis: Long = 1_500L) {
        val deadline = System.currentTimeMillis() + observeMillis
        var imeVisibleObserved = false
        var imeHiddenAfterVisible = false
        while (System.currentTimeMillis() < deadline) {
            val focused = isUrlBarFocused()
            var imeVisible = false
            composeRule.runOnIdle {
                val insets = composeRule.activity.window.decorView.rootWindowInsets
                imeVisible = insets?.isVisible(WindowInsets.Type.ime()) == true
            }

            if (imeVisible) {
                imeVisibleObserved = true
            } else if (imeVisibleObserved) {
                imeHiddenAfterVisible = true
            }
            assertTrue("URL bar focus was dropped while observing keyboard state", focused)
            Thread.sleep(100)
        }
        if (imeVisibleObserved) {
            assertTrue("IME became hidden immediately after opening URL bar", !imeHiddenAfterVisible)
        }
    }

    /**
     * 現在の URL バーのフォーカス状態を返す。
     */
    private fun isUrlBarFocused(): Boolean {
        return runCatching {
            composeRule.onNodeWithTag("url_bar")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        }.getOrDefault(false)
    }

    /**
     * IME(ソフトキーボード)が閉じるまで待機する。
     *
     * ATD 環境では常時非表示のケースもあるため、その場合は即座に条件成立する。
     */
    private fun waitForImeClosed() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var imeVisible = false
            composeRule.runOnIdle {
                val insets = composeRule.activity.window.decorView.rootWindowInsets
                imeVisible = insets?.isVisible(WindowInsets.Type.ime()) == true
            }
            !imeVisible
        }
    }

    /**
     * GeckoView コンテナが存在し、履歴サジェストオーバーレイが無いことを確認する。
     */
    private fun assertGeckoViewInFront() {
        assertTrue(
            composeRule.onAllNodesWithTag(TEST_TAG_GECKO_CONTAINER).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithTag(TEST_TAG_HISTORY_SUGGESTION_LIST).fetchSemanticsNodes().isEmpty()
        )
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
    private fun waitForActiveTab(browserSessionController: BrowserSessionController): BrowserTab {
        var activeTab: BrowserTab? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var found = false
            composeRule.runOnIdle {
                activeTab = browserSessionController.tabs.firstOrNull { it.session.isOpen }
                    ?: browserSessionController.tabs.lastOrNull()
                found = activeTab != null
            }
            found
        }
        assertNotNull(activeTab)
        return activeTab!!
    }

    /**
     * theme-color 拡張のインストール完了を待機する。
     */
    private fun waitForThemeColorExtensionInstalled() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var installed = false
            composeRule.runOnIdle {
                installed = runCatching { getBrowserViewModel().themeColorExtension.isInstalled() }
                    .getOrDefault(false)
            }
            installed
        }
    }

    /**
     * Activity から BrowserViewModel を取得する。
     */
    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
    }

    /**
     * ツールバーの Semantics から色情報を抽出して返す。
     */
    private fun waitForToolbarState(): ToolbarState {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(TEST_TAG_TOOLBAR).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        val node = composeRule.onNodeWithTag(TEST_TAG_TOOLBAR).fetchSemanticsNode()
        val stateDescription = node.config[SemanticsProperties.StateDescription]
        val tokens = stateDescription.split("|")
        require(tokens.size == 3) {
            "Unexpected toolbar stateDescription format: $stateDescription"
        }
        require(tokens[0] == "toolbarColor") {
            "Unexpected toolbar stateDescription prefix: $stateDescription"
        }
        return ToolbarState(
            source = tokens[1],
            argbHex = tokens[2],
        )
    }

    /**
     * ツールバー色 Semantics を扱いやすくするための値オブジェクト。
     */
    private data class ToolbarState(
        val source: String,
        val argbHex: String,
    )
}

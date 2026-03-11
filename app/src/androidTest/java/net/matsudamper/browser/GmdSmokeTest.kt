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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
     * ローカルHTMLを開いたときにツールバーの色ソースが `default` から `theme` に切り替わることを確認する。
     */
    @Test
    fun openHatenablogAndApplyThemeColor() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val localThemeColorPageUri = prepareLocalThemeColorPageUri()
        waitForThemeColorExtensionInstalled()
        val initialToolbarState = waitForToolbarState()
        assertEquals("default", initialToolbarState.source)

        // URLバー経由だと file URI が補正されるため、GeckoSession に直接 file URI を渡す。
        composeRule.runOnIdle {
            activeTab.session.loadUri(localThemeColorPageUri)
        }

        waitForActiveTabUrl(timeoutMillis = 60_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("file:") && currentUrl.contains(LOCAL_THEME_COLOR_INDEX_FILE_NAME)
        }

        composeRule.waitUntil(timeoutMillis = 60_000) {
            waitForToolbarState().source == "theme"
        }

        val resolvedToolbarState = waitForToolbarState()

        composeRule.runOnIdle {
            assertTrue(activeTab.currentUrl.startsWith("file:"))
            assertTrue(activeTab.currentUrl.contains(LOCAL_THEME_COLOR_INDEX_FILE_NAME))
        }
        assertEquals("theme", resolvedToolbarState.source)
        assertNotEquals(initialToolbarState.argbHex, resolvedToolbarState.argbHex)
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
    }

    /**
     * GeckoView 側にフォーカスがある状態でローカルHTMLページを開いた後に URL バーをタップしても、
     * 入力フォーカスが即座に失われないことを確認する。
     *
     * IME が観測期間中に一度でも表示された場合は、その後すぐに閉じていないことも確認する。
     *
     * ※ 外部URL(google.com)への依存を避けるため、ローカルHTMLページを使用する。
     */
    @Test
    fun openingUrlBarFromGeckoViewDoesNotImmediatelyCloseKeyboard() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        val focusPageUri = prepareLocalFocusPageUri()

        // ローカルHTMLを直接ロードする
        composeRule.runOnIdle {
            activeTab.session.loadUri(focusPageUri)
        }

        waitForActiveTabUrl(timeoutMillis = 60_000, activeTab = activeTab) { currentUrl ->
            currentUrl.startsWith("file:") && currentUrl.contains(LOCAL_FOCUS_INDEX_FILE_NAME)
        }
        waitForUrlBarNotFocused()
        assertGeckoViewInFront()

        focusPageSearchInput(activeTab)
        val imeWasVisibleBeforeTap = waitForImeVisible(timeoutMillis = 5_000)

        composeRule.onNodeWithTag("url_bar").performClick()
        waitForUrlBarFocused()
        assertUrlBarFocusAndImeStayStableAfterOpening(
            requireImeWasVisibleBeforeTap = imeWasVisibleBeforeTap,
        )
    }

    /**
     * URLバーで履歴サジェスト表示中に戻るボタンを押したとき、
     * アプリを終了せずに URLバー入力状態だけが閉じることを確認する。
     */
    @Test
    fun backButtonClosesUrlBarWithHistorySuggestionsWithoutExitingApp() {
        val query = "back-history-20260307"
        val suggestionTitle = "Back History Suggestion 20260307"
        val suggestionUrl = "https://$query.example/test"

        seedHistoryEntry(url = suggestionUrl, title = suggestionTitle)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar").performTextReplacement(query)
        waitForHistorySuggestionsVisible(suggestionTitle)

        pressSystemBack()

        waitForHistorySuggestionsHidden()
        waitForUrlBarNotFocused()
        assertGeckoViewInFront()
        composeRule.runOnIdle {
            assertTrue(!composeRule.activity.isFinishing)
        }
    }

    /**
     * テスト用に履歴エントリを 1 件追加する。
     */
    private fun seedHistoryEntry(url: String, title: String) {
        composeRule.runOnIdle {
            runBlocking {
                getBrowserViewModel().historyRepository.recordVisit(url, title)
            }
        }
    }

    /**
     * テーマカラー検証用のローカルHTMLをキャッシュへ展開し、file URI を返す。
     */
    private fun prepareLocalThemeColorPageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, LOCAL_THEME_COLOR_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, LOCAL_THEME_COLOR_INDEX_FILE_NAME)
        assetManager.open("$LOCAL_THEME_COLOR_ASSET_DIR/$LOCAL_THEME_COLOR_INDEX_FILE_NAME").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination.toURI().toString()
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
    private fun assertUrlBarFocusAndImeStayStableAfterOpening(
        observeMillis: Long = 1_500L,
        requireImeWasVisibleBeforeTap: Boolean = false,
    ) {
        val start = System.currentTimeMillis()
        val deadline = start + observeMillis
        val stableWindowStart = start + 700L
        var imeVisibleInStableWindow = false
        while (System.currentTimeMillis() < deadline) {
            val focused = isUrlBarFocused()
            var imeVisible = false
            composeRule.runOnIdle {
                val insets = composeRule.activity.window.decorView.rootWindowInsets
                imeVisible = insets?.isVisible(WindowInsets.Type.ime()) == true
            }

            if (imeVisible && System.currentTimeMillis() >= stableWindowStart) {
                imeVisibleInStableWindow = true
            }
            assertTrue("URL bar focus was dropped while observing keyboard state", focused)
            Thread.sleep(100)
        }
        if (requireImeWasVisibleBeforeTap) {
            assertTrue(
                "IME was visible before tapping URL bar but did not stay visible/reopen for URL bar",
                imeVisibleInStableWindow,
            )
        }
    }

    /**
     * ローカルHTMLの検索入力へ JS でフォーカスを当てる。
     */
    private fun focusPageSearchInput(activeTab: BrowserTab) {
        composeRule.runOnIdle {
            activeTab.session.loadUri(
                "javascript:void((function(){" +
                    "var el=document.querySelector('input[name=q]');" +
                    "if(el){el.focus();}" +
                    "})())",
            )
        }
    }

    /**
     * フォーカステスト用のローカルHTMLをキャッシュへ展開し、file URI を返す。
     */
    private fun prepareLocalFocusPageUri(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, LOCAL_FOCUS_DIR_NAME).apply { mkdirs() }
        val assetManager = instrumentation.context.assets
        val destination = File(destinationDir, LOCAL_FOCUS_INDEX_FILE_NAME)
        assetManager.open("$LOCAL_FOCUS_ASSET_DIR/$LOCAL_FOCUS_INDEX_FILE_NAME").use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination.toURI().toString()
    }

    /**
     * IME が表示されるまで待機し、表示されたかどうかを返す。
     */
    private fun waitForImeVisible(timeoutMillis: Long): Boolean {
        return runCatching {
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                var imeVisible = false
                composeRule.runOnIdle {
                    val insets = composeRule.activity.window.decorView.rootWindowInsets
                    imeVisible = insets?.isVisible(WindowInsets.Type.ime()) == true
                }
                imeVisible
            }
            true
        }.getOrDefault(false)
    }

    /**
     * システムの戻る操作を1回発行する。
     */
    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
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

    companion object {
        private const val LOCAL_THEME_COLOR_ASSET_DIR = "test-theme-color"
        private const val LOCAL_THEME_COLOR_DIR_NAME = "test-theme-color"
        private const val LOCAL_THEME_COLOR_INDEX_FILE_NAME = "index.html"
        private const val LOCAL_FOCUS_ASSET_DIR = "test-focus"
        private const val LOCAL_FOCUS_DIR_NAME = "test-focus"
        private const val LOCAL_FOCUS_INDEX_FILE_NAME = "index.html"
    }
}

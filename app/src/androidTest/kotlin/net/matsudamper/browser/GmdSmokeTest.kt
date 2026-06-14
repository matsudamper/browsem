package net.matsudamper.browser

import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        ensureBrowserScreen()
        val localThemeColorPageUri = prepareLocalThemeColorPageUri()
        val initialToolbarState = waitForToolbarState()
        assertEquals("default", initialToolbarState.source)

        openLocalPage(
            url = localThemeColorPageUri,
            urlMarker = LOCAL_THEME_COLOR_INDEX_FILE_NAME,
        )

        composeRule.waitUntil(timeoutMillis = 60_000) {
            waitForToolbarState().source == "theme"
        }

        val resolvedToolbarState = waitForToolbarState()
        val currentUrl = composeRule.currentUrlBarText()
        assertTrue(currentUrl.startsWith("file:"))
        assertTrue(currentUrl.contains(LOCAL_THEME_COLOR_INDEX_FILE_NAME))
        assertEquals("theme", resolvedToolbarState.source)
        assertNotEquals(initialToolbarState.argbHex, resolvedToolbarState.argbHex)
    }

    /**
     * 履歴エントリを挿入した後、URLバー入力で一致候補が表示されることを確認する。
     */
    @Test
    fun urlBarShowsHistorySuggestions() {
        ensureBrowserScreen()
        val query = "codex-suggest-20260307"
        val suggestionTitle = "Codex Suggest Test Title 20260307"
        val suggestionUrl = "https://$query.example/test"

        seedHistoryEntry(url = suggestionUrl, title = suggestionTitle)

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(query)

        waitForHistorySuggestionsVisible()
    }

    /**
     * URLバーを開くと入力欄が空になり、サジェスト先頭に現在URLの操作行が表示されることを確認する。
     *
     * 併せて「URLバーに戻す」で現在URLを入力欄へ戻せることを確認する。
     */
    @Test
    fun tappingUrlBarClearsInputAndShowsCurrentUrlActions() {
        ensureBrowserScreen()
        val focusPageUri = prepareLocalFocusPageUri()

        openLocalPage(
            url = focusPageUri,
            urlMarker = LOCAL_FOCUS_INDEX_FILE_NAME,
        )
        val currentUrl = composeRule.currentUrlBarText()

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        waitForUrlBarFocused()
        waitForUrlBarText("")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.CurrentUrlActions.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // ListItem の mergeDescendants により子ノードは merged tree で不可視のため unmerged tree を使用
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.CopyButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.RestoreUrlButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // CurrentUrlText の実テキストが捕捉した currentUrl と一致することを直接検証する。
        // urlInput は送信値(file:/)、CurrentUrlText は Gecko 正規化値(file:///)になる
        // タイミングがあり、file URL のスラッシュ表記ゆれで誤検知するため正規化して比較する。
        val displayedUrl = composeRule
            .onNodeWithTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
        assertEquals(
            "現在URL表示が一致しない: currentUrl=\"$currentUrl\" displayedUrl=\"$displayedUrl\"",
            normalizeFileUrl(currentUrl),
            normalizeFileUrl(displayedUrl),
        )

        composeRule.onNodeWithTag(BrowserTabSurfaceTestTags.RestoreUrlButton.testTag).performClick()
        waitForUrlBarText(currentUrl)
    }

    /**
     * 履歴候補が出ている状態でも IME 実行で通常検索 URL へ遷移し、
     * 候補オーバーレイが消えて GeckoView が前面になることを確認する。
     *
     * 併せて URL バーのフォーカス解除と IME 非表示を確認する。
     */
    @Test
    fun searchEngineSearchWithHistorySuggestionsBringsGeckoViewToFront() {
        ensureBrowserScreen()
        val token = "history-search-20260307"
        val searchQuery = "$token normal query"
        val historyTitle = searchQuery
        val historyUrl = "https://$token.example/path"
        val seededUrl = seedHistoryEntry(url = historyUrl, title = historyTitle)

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(searchQuery)
        waitForHistorySuggestionsVisible(historyTitle)

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performImeAction()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            val currentUrl = composeRule.currentUrlBarText()
            currentUrl.contains(token) && !currentUrl.startsWith(seededUrl)
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
        ensureBrowserScreen()
        val token = "history-pick-20260307"
        val historyTitle = "History Pick Seed 20260307"
        val historyUrl = "https://$token.example/path"

        val seededUrl = seedHistoryEntry(url = historyUrl, title = historyTitle)

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(token)
        waitForHistorySuggestionsVisible(historyTitle)

        composeRule.onNodeWithText(historyTitle).performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            // file URL の表記ゆれ（file:/ と file:/// など）で誤検知しないよう、
            // 生成した seed URL 先頭一致またはファイル名トークン一致で判定する。
            val currentUrl = composeRule.currentUrlBarText()
            currentUrl.startsWith(seededUrl) ||
                currentUrl.contains("${HISTORY_SEED_FILE_PREFIX}_${token}")
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
        ensureBrowserScreen()
        val focusPageUri = prepareLocalFocusPageUri()

        openLocalPage(
            url = focusPageUri,
            urlMarker = LOCAL_FOCUS_INDEX_FILE_NAME,
        )
        waitForUrlBarNotFocused()
        assertGeckoViewInFront()

        composeRule.tapGeckoContainer()
        val imeWasVisibleBeforeTap = waitForImeVisible(timeoutMillis = 5_000)

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
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
        ensureBrowserScreen()
        val query = "back-history-20260307"
        val suggestionTitle = "Back History Suggestion 20260307"
        val suggestionUrl = "https://$query.example/test"

        seedHistoryEntry(url = suggestionUrl, title = suggestionTitle)

        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(query)
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
     * 表示失敗後の再読み込みが、直前に成功していたページではなく失敗したURLを再試行することを確認する。
     */
    @Test
    fun retryOnPageLoadErrorRetriesFailedUrl() {
        ensureBrowserScreen()
        val focusPageUri = prepareLocalFocusPageUri()

        openLocalPage(
            url = focusPageUri,
            urlMarker = LOCAL_FOCUS_INDEX_FILE_NAME,
        )
        composeRule.openUrlFromUrlBar(PAGE_LOAD_ERROR_TEST_URL)

        waitForPageLoadErrorVisible(PAGE_LOAD_ERROR_TEST_URL)
        assertEquals(PAGE_LOAD_ERROR_TEST_URL, composeRule.currentUrlBarText())
        waitForUrlBarText(PAGE_LOAD_ERROR_TEST_URL)

        composeRule.onNodeWithTag(BrowserTabSurfaceTestTags.RetryButton.testTag).performClick()

        waitForPageLoadErrorVisible(PAGE_LOAD_ERROR_TEST_URL)
        assertEquals(PAGE_LOAD_ERROR_TEST_URL, composeRule.currentUrlBarText())
        waitForUrlBarText(PAGE_LOAD_ERROR_TEST_URL)
    }

    /**
     * テスト用に履歴エントリを 1 件追加する。
     */
    private fun seedHistoryEntry(url: String, title: String): String {
        val seedPageUri = prepareHistorySeedPageUri(url, title)
        openLocalPage(
            url = seedPageUri,
            urlMarker = HISTORY_SEED_FILE_PREFIX,
        )
        return seedPageUri
    }

    private fun openLocalPage(url: String, urlMarker: String) {
        composeRule.openUrlViaViewIntent(url)
        val openedByIntent = runCatching {
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 20_000)
            true
        }.getOrDefault(false)
        if (!openedByIntent) {
            composeRule.openUrlFromUrlBar(url)
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 60_000)
        }
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
    }

    private fun ensureBrowserScreen() {
        val browserReady = runCatching {
            waitForToolbarState()
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
        waitForToolbarState()
    }

    /**
     * 履歴サジェスト用のローカルページを生成して file URI を返す。
     */
    private fun prepareHistorySeedPageUri(url: String, title: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val destinationDir = File(targetContext.cacheDir, HISTORY_SEED_DIR_NAME).apply { mkdirs() }
        val token = url
            .substringAfter("://", url)
            .substringBefore("/")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val fileName = "${HISTORY_SEED_FILE_PREFIX}_${token}.html"
        val destination = File(destinationDir, fileName)
        destination.writeText(
            """
            <!doctype html>
            <html lang="ja">
              <head>
                <meta charset="utf-8" />
                <title>$title</title>
              </head>
              <body>
                <main>$title</main>
              </body>
            </html>
            """.trimIndent()
        )
        return destination.toURI().toString()
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
    private fun waitForHistorySuggestionsVisible(suggestionTitle: String? = null) {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            val overlayVisible = composeRule
                .onAllNodesWithTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
            val itemVisible = suggestionTitle?.let {
                composeRule
                    .onAllNodesWithText(it)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            } ?: true
            overlayVisible && itemVisible
        }
    }

    /**
     * 履歴サジェストオーバーレイが非表示になるまで待機する。
     */
    private fun waitForHistorySuggestionsHidden() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule
                .onAllNodesWithTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * ページ読み込みエラー画面と失敗URLが表示されるまで待機する。
     */
    private fun waitForPageLoadErrorVisible(failingUrl: String) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(BrowserTabSurfaceTestTags.PageLoadError.testTag)
                .filter(hasAnyDescendant(hasText(failingUrl)))
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.PageLoadError.testTag).fetchSemanticsNodes().isNotEmpty()
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
            composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        }.getOrDefault(false)
    }

    /**
     * 現在の URL バー文字列が期待値になるまで待機する。
     * timeout 時には実際の URL バー値を例外メッセージに含める。
     */
    private fun waitForUrlBarText(expected: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                normalizeFileUrl(composeRule.currentUrlBarText()) == normalizeFileUrl(expected)
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "URL バー復元待機がタイムアウト: expected=\"$expected\" actual=\"${composeRule.currentUrlBarText()}\"",
                e,
            )
        }
    }

    /**
     * file URL のスラッシュ表記ゆれ（file:/ ・ file:// ・ file:///）を file:/// に正規化する。
     * file 以外のスキームはそのまま返す。
     */
    private fun normalizeFileUrl(url: String): String {
        return url.replaceFirst(Regex("^file:/+"), "file:///")
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
            composeRule.onAllNodesWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag).fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * ツールバーの Semantics から色情報を抽出して返す。
     */
    private fun waitForToolbarState(): ToolbarState {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(BrowserToolbarTestTags.Toolbar.testTag).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        val node = composeRule.onNodeWithTag(BrowserToolbarTestTags.Toolbar.testTag).fetchSemanticsNode()
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
        private const val HISTORY_SEED_DIR_NAME = "test-history-seed"
        private const val HISTORY_SEED_FILE_PREFIX = "history-seed"
        private const val PAGE_LOAD_ERROR_TEST_URL = "https://reload-error-test.invalid/"
    }
}

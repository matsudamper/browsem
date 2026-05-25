package net.matsudamper.browser

import android.util.Log
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
        Log.d(TAG, "=== テスト開始: openHatenablogAndApplyThemeColor ===")
        ensureBrowserScreen()
        val localThemeColorPageUri = prepareLocalThemeColorPageUri()
        val initialToolbarState = waitForToolbarState()
        Log.d(TAG, "初期ツールバー状態: source=${initialToolbarState.source}, argb=${initialToolbarState.argbHex}")
        assertEquals("default", initialToolbarState.source)

        Log.d(TAG, "テーマカラーページをオープン: $localThemeColorPageUri")
        openLocalPage(
            url = localThemeColorPageUri,
            urlMarker = LOCAL_THEME_COLOR_INDEX_FILE_NAME,
        )

        Log.d(TAG, "テーマカラー適用を待機中")
        composeRule.waitUntil(timeoutMillis = 60_000) {
            waitForToolbarState().source == "theme"
        }

        val resolvedToolbarState = waitForToolbarState()
        val currentUrl = composeRule.currentUrlBarText()
        Log.d(TAG, "解決後ツールバー状態: source=${resolvedToolbarState.source}, argb=${resolvedToolbarState.argbHex}, url=$currentUrl")
        assertTrue(currentUrl.startsWith("file:"))
        assertTrue(currentUrl.contains(LOCAL_THEME_COLOR_INDEX_FILE_NAME))
        assertEquals("theme", resolvedToolbarState.source)
        assertNotEquals(initialToolbarState.argbHex, resolvedToolbarState.argbHex)
        Log.d(TAG, "=== テスト完了: openHatenablogAndApplyThemeColor ===")
    }

    /**
     * 履歴エントリを挿入した後、URLバー入力で一致候補が表示されることを確認する。
     */
    @Test
    fun urlBarShowsHistorySuggestions() {
        Log.d(TAG, "=== テスト開始: urlBarShowsHistorySuggestions ===")
        ensureBrowserScreen()
        val query = "codex-suggest-20260307"
        val suggestionTitle = "Codex Suggest Test Title 20260307"
        val suggestionUrl = "https://$query.example/test"

        Log.d(TAG, "履歴シード作成: url=$suggestionUrl, title=$suggestionTitle")
        seedHistoryEntry(url = suggestionUrl, title = suggestionTitle)

        Log.d(TAG, "URLバーをタップしてクエリを入力: $query")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(query)

        Log.d(TAG, "履歴サジェスト表示を待機中")
        waitForHistorySuggestionsVisible()
        Log.d(TAG, "=== テスト完了: urlBarShowsHistorySuggestions ===")
    }

    /**
     * URLバーを開くと入力欄が空になり、サジェスト先頭に現在URLの操作行が表示されることを確認する。
     *
     * 併せて「URLバーに戻す」で現在URLを入力欄へ戻せることを確認する。
     */
    @Test
    fun tappingUrlBarClearsInputAndShowsCurrentUrlActions() {
        Log.d(TAG, "=== テスト開始: tappingUrlBarClearsInputAndShowsCurrentUrlActions ===")
        ensureBrowserScreen()
        val focusPageUri = prepareLocalFocusPageUri()

        Log.d(TAG, "フォーカステストページをオープン")
        openLocalPage(
            url = focusPageUri,
            urlMarker = LOCAL_FOCUS_INDEX_FILE_NAME,
        )
        val currentUrl = composeRule.currentUrlBarText()
        Log.d(TAG, "現在のURL: $currentUrl")

        Log.d(TAG, "URLバーをタップ")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        waitForUrlBarFocused()
        waitForUrlBarText("")
        Log.d(TAG, "URLバーフォーカス確認済み、CurrentUrlActionsを待機中")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.CurrentUrlActions.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // ListItem の mergeDescendants により子ノードは merged tree で不可視のため unmerged tree を使用
        Log.d(TAG, "CurrentUrlTextを待機中")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        Log.d(TAG, "CopyButtonを待機中")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.CopyButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        Log.d(TAG, "RestoreUrlButtonを待機中")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(BrowserTabSurfaceTestTags.RestoreUrlButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // CurrentUrlText の実テキストが捕捉した currentUrl と一致することを直接検証する
        val displayedUrl = composeRule
            .onNodeWithTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
        Log.d(TAG, "表示URL検証: expected=\"$currentUrl\" actual=\"$displayedUrl\"")
        assertEquals(currentUrl, displayedUrl)

        Log.d(TAG, "RestoreUrlButtonをタップしてURLを復元")
        composeRule.onNodeWithTag(BrowserTabSurfaceTestTags.RestoreUrlButton.testTag).performClick()
        waitForUrlBarText(currentUrl)
        Log.d(TAG, "=== テスト完了: tappingUrlBarClearsInputAndShowsCurrentUrlActions ===")
    }

    /**
     * 履歴候補が出ている状態でも IME 実行で通常検索 URL へ遷移し、
     * 候補オーバーレイが消えて GeckoView が前面になることを確認する。
     *
     * 併せて URL バーのフォーカス解除と IME 非表示を確認する。
     */
    @Test
    fun searchEngineSearchWithHistorySuggestionsBringsGeckoViewToFront() {
        Log.d(TAG, "=== テスト開始: searchEngineSearchWithHistorySuggestionsBringsGeckoViewToFront ===")
        ensureBrowserScreen()
        val token = "history-search-20260307"
        val searchQuery = "$token normal query"
        val historyTitle = searchQuery
        val historyUrl = "https://$token.example/path"
        Log.d(TAG, "履歴シード作成: url=$historyUrl")
        val seededUrl = seedHistoryEntry(url = historyUrl, title = historyTitle)
        Log.d(TAG, "履歴シード完了: seededUrl=$seededUrl")

        Log.d(TAG, "URLバーをタップしてクエリを入力: $searchQuery")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(searchQuery)
        Log.d(TAG, "履歴サジェスト表示を待機中: $historyTitle")
        waitForHistorySuggestionsVisible(historyTitle)

        Log.d(TAG, "IMEアクション実行（検索）")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performImeAction()

        Log.d(TAG, "検索結果URLへの遷移を待機中 (token=$token)")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val currentUrl = composeRule.currentUrlBarText()
            currentUrl.contains(token) && !currentUrl.startsWith(seededUrl)
        }
        Log.d(TAG, "遷移完了: 現在URL=${composeRule.currentUrlBarText()}")
        waitForHistorySuggestionsHidden()
        waitForUrlBarNotFocused()
        waitForImeClosed()
        assertGeckoViewInFront()
        Log.d(TAG, "=== テスト完了: searchEngineSearchWithHistorySuggestionsBringsGeckoViewToFront ===")
    }

    /**
     * 履歴候補タップ遷移後に候補オーバーレイが消え、
     * GeckoView が前面に戻ることを確認する。
     *
     * 併せて URL バーのフォーカス解除と IME 非表示を確認する。
     */
    @Test
    fun selectingHistorySuggestionBringsGeckoViewToFront() {
        Log.d(TAG, "=== テスト開始: selectingHistorySuggestionBringsGeckoViewToFront ===")
        ensureBrowserScreen()
        val token = "history-pick-20260307"
        val historyTitle = "History Pick Seed 20260307"
        val historyUrl = "https://$token.example/path"

        Log.d(TAG, "履歴シード作成: url=$historyUrl, title=$historyTitle")
        val seededUrl = seedHistoryEntry(url = historyUrl, title = historyTitle)
        Log.d(TAG, "履歴シード完了: seededUrl=$seededUrl")

        Log.d(TAG, "URLバーをタップしてトークンを入力: $token")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(token)
        Log.d(TAG, "履歴サジェスト表示を待機中: $historyTitle")
        waitForHistorySuggestionsVisible(historyTitle)

        Log.d(TAG, "履歴サジェストをタップ: $historyTitle")
        composeRule.onNodeWithText(historyTitle).performClick()

        Log.d(TAG, "シードURLへの遷移を待機中")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            // file URL の表記ゆれ（file:/ と file:/// など）で誤検知しないよう、
            // 生成した seed URL 先頭一致またはファイル名トークン一致で判定する。
            val currentUrl = composeRule.currentUrlBarText()
            currentUrl.startsWith(seededUrl) ||
                currentUrl.contains("${HISTORY_SEED_FILE_PREFIX}_${token}")
        }
        Log.d(TAG, "遷移完了: 現在URL=${composeRule.currentUrlBarText()}")
        waitForHistorySuggestionsHidden()
        waitForUrlBarNotFocused()
        waitForImeClosed()
        assertGeckoViewInFront()
        Log.d(TAG, "=== テスト完了: selectingHistorySuggestionBringsGeckoViewToFront ===")
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
        Log.d(TAG, "=== テスト開始: openingUrlBarFromGeckoViewDoesNotImmediatelyCloseKeyboard ===")
        ensureBrowserScreen()
        val focusPageUri = prepareLocalFocusPageUri()

        Log.d(TAG, "フォーカステストページをオープン")
        openLocalPage(
            url = focusPageUri,
            urlMarker = LOCAL_FOCUS_INDEX_FILE_NAME,
        )
        waitForUrlBarNotFocused()
        assertGeckoViewInFront()

        Log.d(TAG, "GeckoContainerをタップ")
        composeRule.tapGeckoContainer()
        val imeWasVisibleBeforeTap = waitForImeVisible(timeoutMillis = 5_000)
        Log.d(TAG, "タップ前のIME表示状態: $imeWasVisibleBeforeTap")

        Log.d(TAG, "URLバーをタップ")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        waitForUrlBarFocused()
        Log.d(TAG, "URLバーフォーカス確認済み、安定性を観察中")
        assertUrlBarFocusAndImeStayStableAfterOpening(
            requireImeWasVisibleBeforeTap = imeWasVisibleBeforeTap,
        )
        Log.d(TAG, "=== テスト完了: openingUrlBarFromGeckoViewDoesNotImmediatelyCloseKeyboard ===")
    }

    /**
     * URLバーで履歴サジェスト表示中に戻るボタンを押したとき、
     * アプリを終了せずに URLバー入力状態だけが閉じることを確認する。
     */
    @Test
    fun backButtonClosesUrlBarWithHistorySuggestionsWithoutExitingApp() {
        Log.d(TAG, "=== テスト開始: backButtonClosesUrlBarWithHistorySuggestionsWithoutExitingApp ===")
        ensureBrowserScreen()
        val query = "back-history-20260307"
        val suggestionTitle = "Back History Suggestion 20260307"
        val suggestionUrl = "https://$query.example/test"

        Log.d(TAG, "履歴シード作成: url=$suggestionUrl")
        seedHistoryEntry(url = suggestionUrl, title = suggestionTitle)

        Log.d(TAG, "URLバーをタップしてクエリを入力: $query")
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
        composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(query)
        Log.d(TAG, "履歴サジェスト表示を待機中: $suggestionTitle")
        waitForHistorySuggestionsVisible(suggestionTitle)

        Log.d(TAG, "戻るボタンを押下")
        pressSystemBack()

        Log.d(TAG, "サジェスト非表示・URLバー非フォーカスを待機中")
        waitForHistorySuggestionsHidden()
        waitForUrlBarNotFocused()
        assertGeckoViewInFront()
        composeRule.runOnIdle {
            val finishing = composeRule.activity.isFinishing
            Log.d(TAG, "Activity.isFinishing=$finishing")
            assertTrue(!finishing)
        }
        Log.d(TAG, "=== テスト完了: backButtonClosesUrlBarWithHistorySuggestionsWithoutExitingApp ===")
    }

    /**
     * 表示失敗後の再読み込みが、直前に成功していたページではなく失敗したURLを再試行することを確認する。
     */
    @Test
    fun retryOnPageLoadErrorRetriesFailedUrl() {
        Log.d(TAG, "=== テスト開始: retryOnPageLoadErrorRetriesFailedUrl ===")
        ensureBrowserScreen()
        val focusPageUri = prepareLocalFocusPageUri()

        Log.d(TAG, "フォーカステストページをオープン")
        openLocalPage(
            url = focusPageUri,
            urlMarker = LOCAL_FOCUS_INDEX_FILE_NAME,
        )
        Log.d(TAG, "エラーURL（無効ドメイン）へ遷移: $PAGE_LOAD_ERROR_TEST_URL")
        composeRule.openUrlFromUrlBar(PAGE_LOAD_ERROR_TEST_URL)

        Log.d(TAG, "ページロードエラー画面を待機中")
        waitForPageLoadErrorVisible(PAGE_LOAD_ERROR_TEST_URL)
        val urlAfterError = composeRule.currentUrlBarText()
        Log.d(TAG, "エラー画面確認: urlBar=$urlAfterError")
        assertEquals(PAGE_LOAD_ERROR_TEST_URL, urlAfterError)
        waitForUrlBarText(PAGE_LOAD_ERROR_TEST_URL)

        Log.d(TAG, "再試行ボタンをタップ")
        composeRule.onNodeWithTag(BrowserTabSurfaceTestTags.RetryButton.testTag).performClick()

        Log.d(TAG, "再試行後のエラー画面を待機中")
        waitForPageLoadErrorVisible(PAGE_LOAD_ERROR_TEST_URL)
        val urlAfterRetry = composeRule.currentUrlBarText()
        Log.d(TAG, "再試行後エラー確認: urlBar=$urlAfterRetry")
        assertEquals(PAGE_LOAD_ERROR_TEST_URL, urlAfterRetry)
        waitForUrlBarText(PAGE_LOAD_ERROR_TEST_URL)
        Log.d(TAG, "=== テスト完了: retryOnPageLoadErrorRetriesFailedUrl ===")
    }

    /**
     * テスト用に履歴エントリを 1 件追加する。
     */
    private fun seedHistoryEntry(url: String, title: String): String {
        Log.d(TAG, "seedHistoryEntry: url=$url, title=$title")
        val seedPageUri = prepareHistorySeedPageUri(url, title)
        openLocalPage(
            url = seedPageUri,
            urlMarker = HISTORY_SEED_FILE_PREFIX,
        )
        Log.d(TAG, "seedHistoryEntry完了: seedPageUri=$seedPageUri")
        return seedPageUri
    }

    private fun openLocalPage(url: String, urlMarker: String) {
        Log.d(TAG, "openLocalPage: url=$url, marker=$urlMarker")
        composeRule.openUrlViaViewIntent(url)
        val openedByIntent = runCatching {
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 20_000)
            true
        }.getOrDefault(false)
        if (!openedByIntent) {
            Log.d(TAG, "openLocalPage: Intent経由での遷移失敗、URLバー経由で再試行: $url")
            composeRule.openUrlFromUrlBar(url)
            composeRule.waitForUrlBarContains(urlMarker, timeoutMillis = 60_000)
        } else {
            Log.d(TAG, "openLocalPage: Intent経由で遷移成功")
        }
        composeRule.waitForUrlBarNotFocused(timeoutMillis = 30_000)
        Log.d(TAG, "openLocalPage完了: 現在URL=${composeRule.currentUrlBarText()}")
    }

    private fun ensureBrowserScreen() {
        Log.d(TAG, "ensureBrowserScreen: ツールバー確認開始")
        val browserReady = runCatching {
            waitForToolbarState()
            true
        }.getOrDefault(false)
        if (browserReady) {
            Log.d(TAG, "ensureBrowserScreen: ブラウザ画面は既に表示中")
            return
        }

        Log.d(TAG, "ensureBrowserScreen: ツールバー未表示、タブ画面を確認中")
        val tabsReady = runCatching {
            composeRule.waitForTabsScreenLoaded(timeoutMillis = 10_000)
            true
        }.getOrDefault(false)
        if (tabsReady) {
            Log.d(TAG, "ensureBrowserScreen: タブ画面表示中、新規タブを追加")
            composeRule.onNodeWithTag(TabsScreenTestTags.AddTabButton.testTag).performClick()
            composeRule.waitForIdle()
        } else {
            Log.d(TAG, "ensureBrowserScreen: タブ画面も未表示")
        }
        Log.d(TAG, "ensureBrowserScreen: ツールバー表示を待機中")
        waitForToolbarState()
        Log.d(TAG, "ensureBrowserScreen: 完了")
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
        Log.d(TAG, "waitForHistorySuggestionsVisible: title=${suggestionTitle ?: "(任意)"}")
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
        Log.d(TAG, "waitForHistorySuggestionsVisible完了")
    }

    /**
     * 履歴サジェストオーバーレイが非表示になるまで待機する。
     */
    private fun waitForHistorySuggestionsHidden() {
        Log.d(TAG, "waitForHistorySuggestionsHidden")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule
                .onAllNodesWithTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        Log.d(TAG, "waitForHistorySuggestionsHidden完了")
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
                getUrlBarText() == expected
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "URL バー復元待機がタイムアウト: expected=\"$expected\" actual=\"${getUrlBarText()}\"",
                e,
            )
        }
    }

    /**
     * 現在の URL バー文字列を返す。
     */
    private fun getUrlBarText(): String {
        return runCatching {
            composeRule.onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
        }.getOrDefault("")
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
        private const val TAG = "GmdSmokeTest"
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

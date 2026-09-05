package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags

internal fun AndroidComposeTestRule<*, MainActivity>.openUrlViaViewIntent(url: String) {
    val uri = Uri.parse(url)
    runOnIdle {
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                if (uri.scheme == "http" || uri.scheme == "https") {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }
    waitForIdle()
}

internal fun AndroidComposeTestRule<*, MainActivity>.openUrlFromUrlBar(url: String) {
    waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(url)
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performImeAction()
    waitForIdle()
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForUrlBarContains(
    value: String,
    timeoutMillis: Long = 30_000,
) {
    // フォーカス中は urlInput が "" にクリアされ、フォーカス中は onLocationChange でも
    // urlInput を更新しないため、URL バーだけを読むと現在ページ URL を取得できずタイム
    // アウトする。currentPageUrlFromUi() は非フォーカス時は urlInput、フォーカス時は
    // サジェストの現在URL表示(CurrentUrlText=currentPageUrl)を読むことで、フォーカス状態に
    // 依存せず副作用なしで現在ページ URL を取得する（戻る操作による誤遷移リスクを避ける）。
    try {
        waitUntil(timeoutMillis = timeoutMillis) {
            currentPageUrlFromUi().contains(value)
        }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError(
            "waitForUrlBarContains timeout: expected=\"$value\" " +
                "urlInput=\"${currentUrlBarText()}\" currentUrlText=\"${currentUrlActionsText()}\" " +
                "urlBarFocused=${isUrlBarFocused()}",
            e,
        )
    }
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForTabsScreenLoaded(
    timeoutMillis: Long = 20_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(TabsScreenTestTags.AddTabButton.testTag)
            .fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForUrlBarNotFocused(
    timeoutMillis: Long = 30_000,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        if (!isUrlBarFocused()) return
        dismissUrlBarFocusWithoutPageInteraction()
        if (!isUrlBarFocused()) return
        dismissUrlBarFocusViaGeckoContainerIfSafe()
        Thread.sleep(200)
    }
    if (!isUrlBarFocused()) return
    dismissUrlBarFocusViaBackIfStillFocused()
    if (!isUrlBarFocused()) return
    throw AssertionError(
        "waitForUrlBarNotFocused timeout: urlBarFocused=true " +
            "urlInput=\"${currentUrlBarText()}\" currentUrl=\"${currentPageUrlFromUi()}\"",
    )
}

/**
 * ページ遷移や Back を使わず URL バーのフォーカスを外す。
 *
 * IME 非表示はアプリ側 LaunchedEffect (URL_BAR_IME_HIDE_GRACE_MS) 経由で closeUrlInput する。
 * それでも残る場合は ImeAction 送信と currentFocus.clearFocus() を試す。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.dismissUrlBarFocusWithoutPageInteraction() {
    if (!isUrlBarFocused()) return
    hideSoftInputFromDecorView()
    Thread.sleep(URL_BAR_IME_HIDE_GRACE_MS + 100)
    if (!isUrlBarFocused()) return
    runCatching {
        onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performImeAction()
        waitForIdle()
    }
    if (!isUrlBarFocused()) return
    val hasSuggestionOverlay = hasUrlSuggestionOverlay()
    if (hasSuggestionOverlay) {
        runCatching {
            onNodeWithTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag).performClick()
            waitForIdle()
        }
        if (!isUrlBarFocused()) return
    }
    clearCurrentAppFocus()
}

/**
 * サジェストオーバーレイが無いときだけ Gecko コンテナ上端をタップしてフォーカスを移す。
 *
 * 中央タップはページ内ボタン誤操作のリスクがあるため、上端 5% を使う。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.dismissUrlBarFocusViaGeckoContainerIfSafe() {
    if (!isUrlBarFocused()) return
    if (hasUrlSuggestionOverlay()) return
    runCatching {
        onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
            .performTouchInput { click(percentOffset(0.5f, 0.05f)) }
        waitForIdle()
    }
}

/**
 * URL バーがまだフォーカスされている場合のみ Back を送る。
 *
 * runOnIdle 内で直前に再確認し、フォーカス解除済みなら canGoBack 側へ誤送信しない。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.dismissUrlBarFocusViaBackIfStillFocused() {
    runOnIdle {
        if (isUrlBarFocused()) {
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
    waitForIdle()
}

private fun AndroidComposeTestRule<*, MainActivity>.hasUrlSuggestionOverlay(): Boolean {
    return runCatching {
        onAllNodesWithTag(BrowserTabSurfaceTestTags.UrlSuggestionList.testTag)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)
}

private fun AndroidComposeTestRule<*, MainActivity>.clearCurrentAppFocus() {
    runOnIdle {
        activity.currentFocus?.clearFocus()
    }
    waitForIdle()
}

private fun AndroidComposeTestRule<*, MainActivity>.hideSoftInputFromDecorView() {
    runOnIdle {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }
    waitForIdle()
}

internal fun AndroidComposeTestRule<*, MainActivity>.isUrlBarFocused(): Boolean {
    return runCatching {
        onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Focused]
    }.getOrDefault(false)
}

internal fun AndroidComposeTestRule<*, MainActivity>.tapGeckoContainer() {
    onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
        .performTouchInput { click() }
    waitForIdle()
}

internal fun AndroidComposeTestRule<*, MainActivity>.currentUrlBarText(): String {
    return runCatching {
        val config = onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
            .fetchSemanticsNode()
            .config
        // 編集モードは EditableText、表示モード(UrlDisplay)は Text セマンティクスを持つ。
        config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = "") { it.text }
            ?: ""
    }.getOrDefault("")
}

/**
 * フォーカス状態に依存せず現在ページ URL を副作用なしで取得する。
 *
 * - 非フォーカス時: urlInput(EditableText) が現在ページ URL。
 * - フォーカス時: urlInput は "" にクリアされるが、サジェストの「今のURL」表示
 *   (CurrentUrlText) が currentPageUrl をそのまま表示するためそちらを読む。
 *
 * 戻る操作などでフォーカスを外す必要がないため、ページ遷移を誘発しない。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.currentPageUrlFromUi(): String {
    val barText = currentUrlBarText()
    if (barText.isNotEmpty()) return barText
    return currentUrlActionsText()
}

/**
 * サジェストの「今のURL」表示(CurrentUrlText)のテキストを返す。未表示なら空文字。
 *
 * ListItem の mergeDescendants により子ノードは merged tree で不可視のため unmerged tree を使う。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.currentUrlActionsText(): String {
    return runCatching {
        onNodeWithTag(BrowserTabSurfaceTestTags.CurrentUrlText.testTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
    }.getOrDefault("")
}

/**
 * ローカル HTTP サーバーで開いたページが期待どおりかを判定する。
 *
 * セッション復元の遅延コミットでホームページ (google.com) に上書きされる
 * flaky 失敗を検出するため、google.com は常に不一致とする。
 * ファイル名だけでは別ポートの同名ページを区別できないため、origin と path で照合する。
 */
internal fun isExpectedLocalPage(currentUrl: String, expectedPageUrl: String): Boolean {
    if (currentUrl.contains("google.com", ignoreCase = true)) return false
    val expected = Uri.parse(expectedPageUrl)
    val current = Uri.parse(currentUrl)
    val expectedHost = expected.host ?: return false
    if (!expectedHost.equals(current.host, ignoreCase = true)) return false
    if (effectivePort(expected) != effectivePort(current)) return false
    val expectedPath = expected.encodedPath?.trimEnd('/').orEmpty()
    val currentPath = current.encodedPath?.trimEnd('/').orEmpty()
    if (expectedPath.isEmpty()) return true
    return currentPath == expectedPath || currentPath.startsWith("$expectedPath/")
}

private fun effectivePort(uri: Uri): Int {
    if (uri.port != -1) return uri.port
    return when (uri.scheme?.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}

/**
 * ブラウザ画面 (ツールバー) が表示されるまで待機する。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.waitForBrowserReady(
    timeoutMillis: Long = 60_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(BrowserToolbarTestTags.Toolbar.testTag)
            .fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * 起動直後のセッション復元ナビゲーションが落ち着くまで待機する。
 *
 * 復元タブの遅延コミットで URL が変わり続ける間は安定とみなさない。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.waitForSessionNavigationSettled(
    timeoutMillis: Long = 60_000,
    stablePolls: Int = 8,
    pollIntervalMillis: Long = 500,
) {
    var lastUrl: String? = null
    var stableCount = 0
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        val current = currentPageUrlFromUi()
        if (current.isNotEmpty() && current == lastUrl) {
            stableCount++
            if (stableCount >= stablePolls) return
        } else {
            stableCount = 0
            lastUrl = current
        }
        Thread.sleep(pollIntervalMillis)
    }
    throw AssertionError(
        "waitForSessionNavigationSettled timeout: lastUrl=\"$lastUrl\" " +
            "stableCount=$stableCount current=\"${currentPageUrlFromUi()}\"",
    )
}

/**
 * 期待する URL マーカーが一定時間安定するまで待つ (ページの開き直しは行わない)。
 *
 * リロード後の検証など、ナビゲーション操作そのものを検証するケースで使う。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.waitForStablePageMarker(
    expectedPageUrl: String,
    timeoutMillis: Long = 60_000,
    stablePolls: Int = 8,
    pollIntervalMillis: Long = 500,
) {
    var stableCount = 0
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        if (isExpectedLocalPage(currentPageUrlFromUi(), expectedPageUrl)) {
            stableCount++
            if (stableCount >= stablePolls) return
        } else {
            stableCount = 0
        }
        Thread.sleep(pollIntervalMillis)
    }
    throw AssertionError(
        "waitForStablePageMarker timeout: expected=\"$expectedPageUrl\" current=\"${currentPageUrlFromUi()}\"",
    )
}

/**
 * 期待するローカルページ URL が一定時間安定するまで待つ。
 *
 * 復元タブの遅延ナビゲーションで google.com に戻った場合は pageUrl を
 * 開き直して再試行する。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.waitForStableLocalPage(
    pageUrl: String,
    timeoutMillis: Long = 60_000,
    stablePolls: Int = 8,
    pollIntervalMillis: Long = 500,
) {
    var stableCount = 0
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        val current = currentPageUrlFromUi()
        if (isExpectedLocalPage(current, pageUrl)) {
            stableCount++
            if (stableCount >= stablePolls) return
        } else {
            stableCount = 0
            openUrlFromUrlBar(pageUrl)
            waitForIdle()
        }
        Thread.sleep(pollIntervalMillis)
    }
    throw AssertionError(
        "waitForStableLocalPage timeout: expected=\"$pageUrl\" current=\"${currentPageUrlFromUi()}\"",
    )
}

/**
 * ローカルページを開き、URL が安定するまで待つ。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.openLocalPageAndStabilize(
    pageUrl: String,
    timeoutMillis: Long = 60_000,
) {
    waitForBrowserReady()
    waitForSessionNavigationSettled()
    val openedByIntent = runCatching {
        openUrlViaViewIntent(pageUrl)
        waitForUrlBarContains(pageUrl, timeoutMillis = 20_000)
        true
    }.getOrDefault(false)
    if (!openedByIntent) {
        openUrlFromUrlBar(pageUrl)
        waitForUrlBarContains(pageUrl, timeoutMillis = timeoutMillis)
    }
    waitForUrlBarNotFocused()
    waitForStableLocalPage(
        pageUrl = pageUrl,
        timeoutMillis = timeoutMillis,
    )
}

private const val URL_BAR_IME_HIDE_GRACE_MS = 700L

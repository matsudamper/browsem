package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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
            }
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
    // URL バーがフォーカス中は urlInput が "" にクリアされ、さらにフォーカス中は
    // onLocationChange が来ても urlInput を更新しない（BrowserTabScreenState）。
    // この状態のまま読むと現在ページ URL を永遠に取得できずタイムアウトする。
    // アプリの戻る処理（closeUrlInput(true) → restoreCurrentPageUrlToInput）で
    // フォーカスを外し現在 URL を復元してから待機する。
    // GeckoView タップはフォーカス中にサジェストオーバーレイが覆うため復元できない。
    dismissUrlBarFocusViaBack()
    try {
        waitUntil(timeoutMillis = timeoutMillis) {
            currentUrlBarText().contains(value)
        }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError(
            "waitForUrlBarContains timeout: expected=\"$value\" " +
                "actual=\"${currentUrlBarText()}\" urlBarFocused=${isUrlBarFocused()}",
            e,
        )
    }
}

/**
 * URL バーがフォーカス中の場合のみ、アプリの戻る処理でフォーカスを外す。
 *
 * フォーカス中は BackHandler 優先度により closeUrlInput(true) が発火し、
 * URL バー入力だけが閉じて現在ページ URL が復元される（ページ遷移は起きない）。
 * 非フォーカス時は戻るがページ遷移を起こし得るため、フォーカス時のみ実行する。
 */
internal fun AndroidComposeTestRule<*, MainActivity>.dismissUrlBarFocusViaBack() {
    if (!isUrlBarFocused()) return
    runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
    runCatching { waitForUrlBarNotFocused(timeoutMillis = 5_000) }
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
    timeoutMillis: Long = 20_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        !isUrlBarFocused()
    }
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
        onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
    }.getOrDefault("")
}

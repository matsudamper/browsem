package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import net.matsudamper.browser.ui.tabs.TabsScreenTestTags

private const val TAG = "BrowserTestDriver"

internal fun AndroidComposeTestRule<*, MainActivity>.openUrlViaViewIntent(url: String) {
    Log.d(TAG, "openUrlViaViewIntent: $url")
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
    Log.d(TAG, "openUrlViaViewIntent完了: $url")
}

internal fun AndroidComposeTestRule<*, MainActivity>.openUrlFromUrlBar(url: String) {
    Log.d(TAG, "openUrlFromUrlBar: $url")
    waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithTag(UrlTextInputTestTags.UrlBar.testTag).fetchSemanticsNodes().isNotEmpty()
    }
    Log.d(TAG, "openUrlFromUrlBar: URLバー確認、テキスト入力開始")
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(url)
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performImeAction()
    waitForIdle()
    Log.d(TAG, "openUrlFromUrlBar完了: $url")
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForUrlBarContains(
    value: String,
    timeoutMillis: Long = 30_000,
) {
    Log.d(TAG, "waitForUrlBarContains: \"$value\" (timeout=${timeoutMillis}ms, 現在=\"${currentUrlBarText()}\")")
    try {
        waitUntil(timeoutMillis = timeoutMillis) {
            currentUrlBarText().contains(value)
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError(
            "waitForUrlBarContains timeout: expected to contain \"$value\" but got \"${currentUrlBarText()}\"",
            e,
        )
    }
    Log.d(TAG, "waitForUrlBarContains完了: \"$value\" (実値=\"${currentUrlBarText()}\")")
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForTabsScreenLoaded(
    timeoutMillis: Long = 20_000,
) {
    Log.d(TAG, "waitForTabsScreenLoaded (timeout=${timeoutMillis}ms)")
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(TabsScreenTestTags.AddTabButton.testTag)
            .fetchSemanticsNodes().isNotEmpty()
    }
    Log.d(TAG, "waitForTabsScreenLoaded完了")
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForUrlBarNotFocused(
    timeoutMillis: Long = 20_000,
) {
    Log.d(TAG, "waitForUrlBarNotFocused (timeout=${timeoutMillis}ms)")
    waitUntil(timeoutMillis = timeoutMillis) {
        !runCatching {
            onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        }.getOrDefault(false)
    }
    Log.d(TAG, "waitForUrlBarNotFocused完了")
}

internal fun AndroidComposeTestRule<*, MainActivity>.tapGeckoContainer() {
    Log.d(TAG, "tapGeckoContainer")
    onNodeWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
        .performTouchInput { click() }
    waitForIdle()
    Log.d(TAG, "tapGeckoContainer完了")
}

internal fun AndroidComposeTestRule<*, MainActivity>.currentUrlBarText(): String {
    return runCatching {
        onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
    }.getOrDefault("")
}

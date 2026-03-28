package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import net.matsudamper.browser.screen.tab.TabsScreenTestTags

internal fun AndroidComposeTestRule<*, MainActivity>.openUrlViaViewIntent(url: String) {
    runOnIdle {
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        )
    }
    waitForIdle()
}

internal fun AndroidComposeTestRule<*, MainActivity>.openUrlFromUrlBar(url: String) {
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performClick()
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performTextReplacement(url)
    onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag).performImeAction()
    waitForIdle()
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForUrlBarContains(
    value: String,
    timeoutMillis: Long = 30_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        currentUrlBarText().contains(value)
    }
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForTabsScreenLoaded(
    timeoutMillis: Long = 20_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(TabsScreenTestTags.AddTabButton.testTag)
            .fetchSemanticsNodes().isNotEmpty() &&
            onAllNodesWithTag(TabsScreenTestTags.AddTabGroupButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun AndroidComposeTestRule<*, MainActivity>.waitForUrlBarNotFocused(
    timeoutMillis: Long = 20_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        !runCatching {
            onNodeWithTag(UrlTextInputTestTags.UrlBar.testTag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Focused]
        }.getOrDefault(false)
    }
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

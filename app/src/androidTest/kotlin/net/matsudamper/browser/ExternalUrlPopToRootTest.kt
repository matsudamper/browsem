package net.matsudamper.browser

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.seconds
import net.matsudamper.browser.ui.settings.SettingsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 設定画面表示中に外部 URL を受け取った場合、設定を閉じてメイン画面で URL を開くことを検証する。
 */
@RunWith(AndroidJUnit4::class)
class ExternalUrlPopToRootTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun externalUrlShouldPopSettingsAndOpenInMainBrowser() {
        waitForBrowserScreen()
        openSettingsScreen()

        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag(SettingsScreenTestTags.Root.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.openUrlViaViewIntent("https://www.example.com".toUri().toString())

        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag(SettingsScreenTestTags.Root.testTag)
                .fetchSemanticsNodes().isEmpty()
        }
        waitForBrowserScreen()
        composeRule.waitForUrlBarContains("www.example.com", timeoutMillis = 30_000)
    }

    private fun waitForBrowserScreen() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasTestTag(BrowserToolbarTestTags.Toolbar.testTag))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSettingsScreen() {
        composeRule.onNodeWithTag(BrowserToolbarTestTags.MenuButton.testTag).performClick()
        composeRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) {
            composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.SettingsButton.testTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrowserToolbarMenuTestTags.SettingsButton.testTag).performClick()
        composeRule.waitForIdle()
    }
}

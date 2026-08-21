package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.ui.settings.SiteSettingsScreenTestTags
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebAppActivityLaunchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test(timeout = 45_000L)
    fun pinnedWebAppIntentOpensWebAppScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://webapp-launch-test.invalid/")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        ActivityScenario.launch<WebAppActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.Toolbar.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(GeckoBrowserTabTestTags.GeckoContainer.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            scenario.onActivity { activity ->
                assertFalse("WebAppActivity が起動直後に終了しています", activity.isFinishing)
            }
        }
    }

    /**
     * ウェブアプリモードのツールバーメニューにホームボタンが表示されることを確認する。
     */
    @Test(timeout = 45_000L)
    fun webAppMenuShowsHomeButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://webapp-home-button-test.invalid/")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        ActivityScenario.launch<WebAppActivity>(intent).use {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.MenuButton.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithTag(CustomTabToolbarTestTags.MenuButton.testTag).performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.HomeButton.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    /**
     * ウェブアプリモードのツールバーメニューから「サイトの設定」を開けることを確認する。
     */
    @Test(timeout = 45_000L)
    fun webAppMenuOpensSiteSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://webapp-site-settings-test.invalid/")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        ActivityScenario.launch<WebAppActivity>(intent).use {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.MenuButton.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithTag(CustomTabToolbarTestTags.MenuButton.testTag).performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(BrowserToolbarMenuTestTags.SiteSettingsButton.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithTag(BrowserToolbarMenuTestTags.SiteSettingsButton.testTag).performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(SiteSettingsScreenTestTags.Root.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }
}

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
import net.matsudamper.browser.ui.settings.site.SiteSettingsScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTabActivityLaunchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    /**
     * カスタムタブのツールバーメニューから「サイトの設定」を開けることを確認する。
     */
    @Test(timeout = 45_000L)
    fun customTabMenuOpensSiteSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, CustomTabActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://customtab-site-settings-test.invalid/")
        }

        ActivityScenario.launch<CustomTabActivity>(intent).use {
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

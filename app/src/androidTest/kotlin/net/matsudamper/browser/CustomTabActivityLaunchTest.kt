package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.matsudamper.browser.ui.settings.site.SiteSettingsScreenTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTabActivityLaunchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    /**
     * カスタムタブが edge-to-edge で表示され、コンテンツがステータスバーの裏まで描画されることを確認する。
     */
    @Test(timeout = 45_000L)
    fun customTabDrawsBehindStatusBar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, CustomTabActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://customtab-edge-to-edge-test.invalid/")
        }

        ActivityScenario.launch<CustomTabActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.MenuButton.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            var contentTopOnScreen = -1
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                val location = IntArray(2)
                content.getLocationOnScreen(location)
                contentTopOnScreen = location[1]
            }
            assertEquals(0, contentTopOnScreen)
        }
    }

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

    /**
     * サイトの設定から戻ったあとも Custom Tab のセッションが維持されることを確認する。
     * 戻る前にタブ ID を記録し、戻った後も同一タブが controller に残っていることを検証する。
     */
    @Test(timeout = 45_000L)
    fun customTabPreservesSessionAfterReturningFromSiteSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, CustomTabActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://customtab-site-settings-return-test.invalid/")
        }

        ActivityScenario.launch<CustomTabActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.MenuButton.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            var tabIdBeforeSiteSettings: String? = null
            scenario.onActivity { activity ->
                tabIdBeforeSiteSettings = activity.browserTabControllerForTesting().tabs.single().tabId
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

            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.Toolbar.testTag)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            scenario.onActivity { activity ->
                val controller = activity.browserTabControllerForTesting()
                val tabs = controller.tabs
                assertEquals("タブが重複作成されています", 1, tabs.size)
                assertEquals(
                    "サイトの設定から戻った後も同一タブが維持される必要があります",
                    tabIdBeforeSiteSettings,
                    tabs.single().tabId,
                )
            }
        }
    }
}

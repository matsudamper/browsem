package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}

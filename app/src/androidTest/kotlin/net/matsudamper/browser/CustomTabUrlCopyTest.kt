package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTabUrlCopyTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test(timeout = 45_000L)
    fun customTabPageInfoLongPressCopiesCurrentUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedUrl = "https://customtab-copy-url-test.invalid/"
        val clipboard = prepareClipboard(context)
        val intent = Intent(context, CustomTabActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(expectedUrl)
        }

        ActivityScenario.launch<CustomTabActivity>(intent).use {
            performPageInfoLongClick()
            assertClipboardUrl(context, clipboard, expectedUrl)
        }
    }

    @Test(timeout = 45_000L)
    fun webAppPageInfoLongPressCopiesCurrentUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedUrl = "https://webapp-copy-url-test.invalid/"
        val clipboard = prepareClipboard(context)
        val intent = Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(expectedUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        ActivityScenario.launch<WebAppActivity>(intent).use {
            performPageInfoLongClick()
            assertClipboardUrl(context, clipboard, expectedUrl)
        }
    }

    private fun performPageInfoLongClick() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(CustomTabToolbarTestTags.PageInfo.testTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag(CustomTabToolbarTestTags.PageInfo.testTag)
            .performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun prepareClipboard(context: Context): ClipboardManager {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "before"))
        return clipboard
    }

    private fun assertClipboardUrl(
        context: Context,
        clipboard: ClipboardManager,
        expectedUrl: String,
    ) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == expectedUrl
        }
        assertEquals(
            expectedUrl,
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString(),
        )
    }
}

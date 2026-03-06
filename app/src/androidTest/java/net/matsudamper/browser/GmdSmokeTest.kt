package net.matsudamper.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GmdSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun openHatenablogAndApplyThemeColor() {
        val browserSessionController = waitForBrowserSessionController()
        val activeTab = waitForActiveTab(browserSessionController)
        waitForThemeColorExtensionInstalled()
        val initialToolbarState = waitForToolbarState()
        assertEquals("default", initialToolbarState.source)

        composeRule.onNodeWithTag("url_bar").performClick()
        composeRule.onNodeWithTag("url_bar")
            .performTextReplacement("https://matsudamper.hatenablog.jp/")
        composeRule.onNodeWithTag("url_bar").performImeAction()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            var loaded = false
            composeRule.runOnIdle {
                loaded = activeTab.currentUrl.startsWith("https://matsudamper.hatenablog.jp")
            }
            loaded
        }

        composeRule.waitUntil(timeoutMillis = 60_000) {
            var titleResolved = false
            composeRule.runOnIdle {
                titleResolved = activeTab.title.isNotBlank() &&
                    activeTab.title != activeTab.currentUrl
            }
            titleResolved
        }

        composeRule.waitUntil(timeoutMillis = 60_000) {
            runCatching {
                waitForToolbarState().source == "theme"
            }.getOrDefault(false)
        }

        val resolvedToolbarState = waitForToolbarState()

        composeRule.runOnIdle {
            assertTrue(activeTab.currentUrl.startsWith("https://matsudamper.hatenablog.jp"))
            assertTrue(activeTab.title.isNotBlank())
            assertNotEquals(activeTab.currentUrl, activeTab.title)
        }
        assertEquals("theme", resolvedToolbarState.source)
        assertNotEquals(initialToolbarState.argbHex, resolvedToolbarState.argbHex)
    }

    private fun waitForBrowserSessionController(): BrowserSessionController {
        var controller: BrowserSessionController? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var resolved = false
            composeRule.runOnIdle {
                resolved = runCatching {
                    controller = getBrowserViewModel().browserSessionController
                }.isSuccess
            }
            resolved
        }
        return requireNotNull(controller)
    }

    private fun waitForActiveTab(browserSessionController: BrowserSessionController): BrowserTab {
        var activeTab: BrowserTab? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var found = false
            composeRule.runOnIdle {
                activeTab = browserSessionController.tabs.firstOrNull { it.session.isOpen }
                    ?: browserSessionController.tabs.lastOrNull()
                found = activeTab != null
            }
            found
        }
        assertNotNull(activeTab)
        return activeTab!!
    }

    private fun waitForThemeColorExtensionInstalled() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            var installed = false
            composeRule.runOnIdle {
                installed = runCatching { getBrowserViewModel().themeColorExtension.isInstalled() }
                    .getOrDefault(false)
            }
            installed
        }
    }

    private fun getBrowserViewModel(): BrowserViewModel {
        return ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]
    }

    private fun waitForToolbarState(): ToolbarState {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(TEST_TAG_TOOLBAR).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        val node = composeRule.onNodeWithTag(TEST_TAG_TOOLBAR).fetchSemanticsNode()
        val stateDescription = node.config[SemanticsProperties.StateDescription]
        val tokens = stateDescription.split("|")
        require(tokens.size == 3) {
            "Unexpected toolbar stateDescription format: $stateDescription"
        }
        require(tokens[0] == "toolbarColor") {
            "Unexpected toolbar stateDescription prefix: $stateDescription"
        }
        return ToolbarState(
            source = tokens[1],
            argbHex = tokens[2],
        )
    }

    private data class ToolbarState(
        val source: String,
        val argbHex: String,
    )

}

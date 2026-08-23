package net.matsudamper.browser.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class NavControllerTest {

    @Test
    fun replaceCurrentBrowserTab_keepsTabsScreenAndUpdatesBackTarget() {
        val backStack = NavBackStack<NavKey>(
            BrowserNavDestination.Browser(tabId = "tab-a", beforeTab = null),
            BrowserNavDestination.Tabs,
        )
        val navController = NavController(backStack)

        navController.replaceCurrentBrowserTab("tab-b")

        assertEquals(
            listOf(
                BrowserNavDestination.Browser(tabId = "tab-b", beforeTab = null),
                BrowserNavDestination.Tabs,
            ),
            backStack.toList(),
        )

        navController.back()

        assertEquals(
            listOf(BrowserNavDestination.Browser(tabId = "tab-b", beforeTab = null)),
            backStack.toList(),
        )
    }

    @Test
    fun syncToSelectedTab_replacesSetupWithSelectedTab() {
        val backStack = NavBackStack<NavKey>(BrowserNavDestination.Setup)
        val navController = NavController(backStack)

        navController.syncToSelectedTab("tab-a")

        assertEquals(
            listOf(BrowserNavDestination.Browser(tabId = "tab-a", beforeTab = null)),
            backStack.toList(),
        )
    }

    @Test
    fun syncToSelectedTab_correctsMismatchedBrowserDestination() {
        val backStack = NavBackStack<NavKey>(
            BrowserNavDestination.Browser(tabId = "tab-stale", beforeTab = null),
        )
        val navController = NavController(backStack)

        navController.syncToSelectedTab("tab-current")

        assertEquals(
            listOf(BrowserNavDestination.Browser(tabId = "tab-current", beforeTab = null)),
            backStack.toList(),
        )
    }

    @Test
    fun syncToSelectedTab_keepsMatchingBrowserDestination() {
        val destination = BrowserNavDestination.Browser(tabId = "tab-a", beforeTab = null)
        val backStack = NavBackStack<NavKey>(destination)
        val navController = NavController(backStack)

        navController.syncToSelectedTab("tab-a")

        assertEquals(listOf(destination), backStack.toList())
    }

    @Test
    fun syncToSelectedTab_keepsTabsScreenWhenCorrectingUnderlyingTab() {
        val backStack = NavBackStack<NavKey>(
            BrowserNavDestination.Browser(tabId = "tab-stale", beforeTab = null),
            BrowserNavDestination.Tabs,
        )
        val navController = NavController(backStack)

        navController.syncToSelectedTab("tab-current")

        assertEquals(
            listOf(
                BrowserNavDestination.Browser(tabId = "tab-current", beforeTab = null),
                BrowserNavDestination.Tabs,
            ),
            backStack.toList(),
        )
    }
}

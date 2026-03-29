package net.matsudamper.browser.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class NavControllerTest {

    @Test
    fun replaceCurrentBrowserTab_keepsTabsScreenAndUpdatesBackTarget() {
        val backStack = NavBackStack<NavKey>(
            AppDestination.Browser(tabId = "tab-a", beforeTab = null),
            AppDestination.Tabs,
        )
        val navController = NavController(backStack)

        navController.replaceCurrentBrowserTab("tab-b")

        assertEquals(
            listOf(
                AppDestination.Browser(tabId = "tab-b", beforeTab = null),
                AppDestination.Tabs,
            ),
            backStack.toList(),
        )

        navController.back()

        assertEquals(
            listOf(AppDestination.Browser(tabId = "tab-b", beforeTab = null)),
            backStack.toList(),
        )
    }
}

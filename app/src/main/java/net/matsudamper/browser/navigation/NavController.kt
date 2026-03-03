package net.matsudamper.browser.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

@Stable
class NavController(
    private val backStack: NavBackStack<NavKey>,
) {
    fun selectTab(tabId: String) {
        backStack.clear()
        backStack.add(AppDestination.Browser(tabId))
    }

    /**
     * @return tabId
     */
    fun getSelectedTab(): String? {
        return backStack.filterIsInstance<AppDestination.Browser>()
            .lastOrNull()
            ?.tabId
    }

    fun disposeTabs() {
        backStack.removeAll { it is AppDestination.Tabs }
    }
}
package net.matsudamper.browser.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

@Stable
class NavController(
    private val backStack: NavBackStack<NavKey>,
) {
    fun selectTab(tabId: String) {
        backStack.removeAll { it is AppDestination.Browser }
        backStack.add(AppDestination.Browser(tabId))
    }
}
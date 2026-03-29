package net.matsudamper.browser.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

@Stable
class NavController(
    private val backStack: NavBackStack<NavKey>,
) {

    val isLastBackHandled: Boolean
        get() {
            if (backStack.size != 1) return false
            val stack = backStack.getOrNull(0) ?: return false
            if (stack !is AppDestination.Browser) return false
            return stack.beforeTab != null
        }

    fun selectTab(tabId: String, beforeTab: AppDestination.Browser? = null) {
        val destination = AppDestination.Browser(tabId, beforeTab)
        if (backStack.isEmpty()) {
            backStack.add(destination)
            return
        }
        backStack[0] = destination
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun replaceCurrentBrowserTab(tabId: String, beforeTab: AppDestination.Browser? = null) {
        val destination = AppDestination.Browser(tabId, beforeTab)
        val browserIndex = backStack.indexOfLast { it is AppDestination.Browser }
        if (browserIndex < 0) {
            selectTab(tabId, beforeTab)
            return
        }
        backStack[browserIndex] = destination
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

    fun back() {
        if (backStack.size == 1) {
            val stack = backStack.getOrNull(0) ?: return
            if (stack !is AppDestination.Browser) return
            stack.beforeTab ?: return
            backStack.add(stack.beforeTab)
            backStack.removeAt(0)
            return
        }
        backStack.removeLastOrNull()
    }
}

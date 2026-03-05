package net.matsudamper.browser.navigation

import android.util.Log
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
        backStack.clear()
        backStack.add(AppDestination.Browser(tabId, beforeTab))
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
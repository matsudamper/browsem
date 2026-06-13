package net.matsudamper.browser.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * 本体ブラウザの内側ナビゲーションを制御する。
 * タブ切替・beforeTab 履歴・Tabs 画面の管理を閉じ込める。
 */
@Stable
class NavController(
    private val backStack: NavBackStack<NavKey>,
) {

    val isLastBackHandled: Boolean
        get() {
            if (backStack.size != 1) return false
            val stack = backStack.getOrNull(0) ?: return false
            if (stack !is BrowserNavDestination.Browser) return false
            return stack.beforeTab != null
        }

    fun selectTab(tabId: String, beforeTab: BrowserNavDestination.Browser? = null) {
        val destination = BrowserNavDestination.Browser(tabId, beforeTab)
        if (backStack.isEmpty()) {
            backStack.add(destination)
            return
        }
        backStack[0] = destination
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun replaceCurrentBrowserTab(tabId: String, beforeTab: BrowserNavDestination.Browser? = null) {
        val destination = BrowserNavDestination.Browser(tabId, beforeTab)
        val browserIndex = backStack.indexOfLast { it is BrowserNavDestination.Browser }
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
        return backStack.filterIsInstance<BrowserNavDestination.Browser>()
            .lastOrNull()
            ?.tabId
    }

    fun disposeTabs() {
        backStack.removeAll { it is BrowserNavDestination.Tabs }
    }

    fun back() {
        if (backStack.size == 1) {
            val stack = backStack.getOrNull(0) ?: return
            if (stack !is BrowserNavDestination.Browser) return
            stack.beforeTab ?: return
            backStack.add(stack.beforeTab)
            backStack.removeAt(0)
            return
        }
        backStack.removeLastOrNull()
    }
}

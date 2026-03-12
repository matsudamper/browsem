package net.matsudamper.browser.core

object TabSelectionPolicy {
    fun resolveNextSelectedTab(
        closingTabId: String,
        state: TabStoreState,
    ): String? {
        val closingTab = state.tabs.firstOrNull { it.id == closingTabId } ?: return state.selectedTabId
        val remainingTabs = state.tabs.filterNot { it.id == closingTabId }
        if (remainingTabs.isEmpty()) {
            return null
        }
        val selectedTabId = state.selectedTabId
        if (selectedTabId != null && selectedTabId != closingTabId) {
            return remainingTabs.firstOrNull { it.id == selectedTabId }?.id
        }
        val openerTabId = closingTab.openerTabId
        if (openerTabId != null) {
            val openerTab = remainingTabs.firstOrNull { it.id == openerTabId }
            if (openerTab != null) {
                return openerTab.id
            }
        }
        return remainingTabs.last().id
    }
}

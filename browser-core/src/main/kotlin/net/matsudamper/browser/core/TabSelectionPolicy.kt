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
        // 同グループ内のタブを優先する
        val closingTabGroupId = state.tabGroupAssignments[closingTabId]
        if (closingTabGroupId != null) {
            val closingTabIndex = state.tabs.indexOfFirst { it.id == closingTabId }
            val sameGroupTabs = remainingTabs.filter { state.tabGroupAssignments[it.id] == closingTabGroupId }
            if (sameGroupTabs.isNotEmpty()) {
                // 閉じるタブの前にある同グループのタブを選択
                val previousInGroup = sameGroupTabs.lastOrNull { tab ->
                    state.tabs.indexOfFirst { it.id == tab.id } < closingTabIndex
                }
                if (previousInGroup != null) return previousInGroup.id
                // 前がない場合は同グループの次のタブ
                return sameGroupTabs.first().id
            }
        }
        return remainingTabs.last().id
    }
}

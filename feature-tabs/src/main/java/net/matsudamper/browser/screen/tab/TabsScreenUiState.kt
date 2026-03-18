package net.matsudamper.browser.screen.tab

import net.matsudamper.browser.data.TabGroupData

data class TabsScreenUiState(
    val callbacks: Callbacks,
    val loadingState: LoadingState,
) {
    interface Callbacks {
        fun onCloseTab(tabId: String)
        fun onReorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int)
        fun onReorderGroups(fromIndex: Int, toIndex: Int)
        fun onGroupSelected(index: Int)
        fun onGroupPageChanged(page: Int)
        fun onAddGroup()
        fun onMoveTabToGroup(tabId: String, targetGroupIndex: Int)
        fun onRenameGroup(groupIndex: Int, newName: String)
        fun onDeleteGroup(groupIndex: Int)
    }

    sealed interface LoadingState {
        object Loading : LoadingState
        data class Loaded(
            val groupedTabs: List<List<TabsScreenTabData>>,
            val groups: List<TabGroupData>,
            val activeGroupIndex: Int,
        ) : LoadingState
    }
}

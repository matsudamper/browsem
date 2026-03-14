package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupRepository


class TabsScreenViewModel(
    private val browserSessionController: BrowserSessionController,
    private val tabGroupRepository: TabGroupRepository,
) : ViewModel() {

    /**
     * ドラッグ中のグループ並び順をローカルで保持する。
     * null のときは DB の順序をそのまま使う。
     */
    private val _localGroupOrder = MutableStateFlow<List<TabGroupData>?>(null)

    /** グループ一覧。ドラッグ中はローカル順序を優先し、DB の更新が遅れても表示が乱れないようにする。 */
    val groups: StateFlow<List<TabGroupData>> = combine(
        tabGroupRepository.observeGroups(),
        _localGroupOrder,
    ) { dbGroups, localOrder ->
        localOrder ?: dbGroups
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )

    /** 現在アクティブなグループのインデックス */
    private val _activeGroupIndex = MutableStateFlow(0)
    val activeGroupIndex: StateFlow<Int> = _activeGroupIndex.asStateFlow()

    /**
     * グループ別のタブリスト。
     * groups・tabStoreState・タブグループ割り当ての3つを combine して算出する。
     */
    val groupedTabs: StateFlow<List<List<TabsScreenTabData>>> = combine(
        groups,
        browserSessionController.tabStoreState,
        tabGroupRepository.observeTabGroupAssignments(),
    ) { groups, tabState, assignments ->
        val assignmentMap = assignments.associate { it.tabId to it.groupId }
        groups.map { group ->
            tabState.tabs
                .filter { assignmentMap[it.id] == group.id.value }
                .map { tab ->
                    TabsScreenTabData(
                        id = tab.id,
                        title = tab.title,
                        previewBitmapArray = tab.previewBitmapArray,
                    )
                }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )

    init {
        viewModelScope.launch {
            // 初回: デフォルトグループを作成する（DBが空のときのみ）
            val initialTabs = browserSessionController.tabStoreState.first()
            tabGroupRepository.createDefaultGroupIfEmpty(initialTabs.tabs.map { it.id })
        }
        viewModelScope.launch {
            // 新規タブを監視してアクティブグループに自動割り当てする
            var previousTabIds = browserSessionController.tabStoreState.value.tabs.map { it.id }.toSet()
            browserSessionController.tabStoreState.collect { state ->
                val currentIds = state.tabs.map { it.id }.toSet()
                val newIds = currentIds - previousTabIds
                newIds.forEach { tabId ->
                    val activeGroup = groups.value.getOrNull(_activeGroupIndex.value) ?: return@forEach
                    tabGroupRepository.assignTabToGroup(tabId, activeGroup.id)
                }
                previousTabIds = currentIds
            }
        }
    }

    /** グループを選択する（タブバーのタップ時） */
    fun onGroupSelected(index: Int) {
        if (_activeGroupIndex.value == index) return
        _activeGroupIndex.value = index.coerceIn(0, groups.value.lastIndex.coerceAtLeast(0))
    }

    /** ページスワイプ時にグループインデックスを同期する */
    fun onGroupPageChanged(page: Int) {
        if (_activeGroupIndex.value == page) return
        _activeGroupIndex.value = page.coerceIn(0, groups.value.lastIndex.coerceAtLeast(0))
    }

    /** 新しいグループを追加する */
    fun addGroup() {
        viewModelScope.launch {
            val currentGroups = groups.value
            val newSortOrder = currentGroups.size
            val name = "グループ ${newSortOrder + 1}"
            val newId = tabGroupRepository.addGroup(name, newSortOrder)
            // ローカル順序に追加してすぐに反映する
            _localGroupOrder.value = currentGroups + TabGroupData(newId, name)
            _activeGroupIndex.value = newSortOrder
        }
    }

    /**
     * グループを長押しドラッグで並び替える。
     * ローカル順序をすぐに更新して UI を即時反映し、DB へも非同期で保存する。
     */
    fun reorderGroups(fromIndex: Int, toIndex: Int) {
        val currentGroups = (_localGroupOrder.value ?: groups.value).toMutableList()
        if (fromIndex !in currentGroups.indices || toIndex !in currentGroups.indices) return
        currentGroups.add(toIndex, currentGroups.removeAt(fromIndex))
        _localGroupOrder.value = currentGroups

        // アクティブグループのインデックスを並び替えに合わせて補正する
        val active = _activeGroupIndex.value
        _activeGroupIndex.value = when {
            active == fromIndex -> toIndex
            fromIndex < toIndex && active in (fromIndex + 1)..toIndex -> active - 1
            fromIndex > toIndex && active in toIndex until fromIndex -> active + 1
            else -> active
        }

        viewModelScope.launch {
            tabGroupRepository.reorderGroups(currentGroups.map { it.id.value })
        }
    }

    /** タブが閉じられたときにグループ割り当てを解除する */
    fun onTabClosed(tabId: String) {
        viewModelScope.launch {
            tabGroupRepository.removeTabFromGroup(tabId)
        }
    }

    /**
     * グループ内でタブを並び替える。
     * グローバルリストはグループ順に連結した順序で同期する。
     */
    fun reorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) {
        val currentGroupedTabs = groupedTabs.value
        val tabsInGroup = currentGroupedTabs.getOrNull(groupIndex) ?: return
        val reordered = tabsInGroup.toMutableList().also {
            it.add(toLocalIndex, it.removeAt(fromLocalIndex))
        }
        // グローバルリストをグループ連結順で再構築する
        val globalOrder = currentGroupedTabs.flatMapIndexed { idx, tabs ->
            if (idx == groupIndex) reordered else tabs
        }
        globalOrder.forEachIndexed { targetIdx, tab ->
            val currentIdx = browserSessionController.tabStoreState.value.tabs.indexOfFirst { it.id == tab.id }
            if (currentIdx >= 0 && currentIdx != targetIdx) {
                browserSessionController.moveTab(currentIdx, targetIdx)
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class TabsScreenTabData(
    val id: String,
    val title: String,
    val previewBitmapArray: ByteArray?,
)

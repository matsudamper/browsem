package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.tab.TabGroupAssignment


class TabsScreenViewModel(
    private val tabStore: TabStore,
    private val tabGroupRepository: TabGroupRepository,
) : ViewModel() {

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())

    /**
     * onGroupSelected で設定したプログラム的スクロールの目標ページ。
     * Pager アニメーション中に settledPage が中間ページを報告した場合に
     * activeGroupIndex を誤って上書きしないようにするためのガード。
     */
    private var programmaticScrollTarget: Int? = null

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : TabsScreenUiState.Callbacks {
        override fun onCloseTab(tabId: String) {
            // イベントを先に送信してタブを閉じてから、グループ割り当てを解除する
            eventHandler.trySend { it.closeTab(tabId) }
            onTabClosed(tabId)
        }

        override fun onReorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) {
            reorderTabs(groupIndex, fromLocalIndex, toLocalIndex)
        }

        override fun onReorderGroups(fromIndex: Int, toIndex: Int) {
            reorderGroups(fromIndex, toIndex)
        }

        override fun onGroupSelected(index: Int) {
            this@TabsScreenViewModel.onGroupSelected(index)
        }

        override fun onGroupPageChanged(page: Int) {
            this@TabsScreenViewModel.onGroupPageChanged(page)
        }

        override fun onAddGroup() {
            addGroup()
        }

        override fun onMoveTabToGroup(tabId: String, targetGroupIndex: Int) {
            moveTabToGroup(tabId, targetGroupIndex)
        }

        override fun onRenameGroup(groupIndex: Int, newName: String) {
            renameGroup(groupIndex, newName)
        }

        override fun onDeleteGroup(groupIndex: Int) {
            deleteGroup(groupIndex)
        }

        override fun onToggleDefaultGroup(groupIndex: Int) {
            toggleDefaultGroup(groupIndex)
        }
    }

    val uiState: StateFlow<TabsScreenUiState> = viewModelStateFlow
        .map { state ->
            val groups = state.groups
            val assignmentMap = state.assignments.associate { it.tabId to it.groupId }
            val groupedTabs = groups.map { group ->
                state.tabStoreState.tabs
                    .filter { assignmentMap[it.id] == group.id.value }
                    .map { tab ->
                        TabsScreenTabData(
                            id = tab.id,
                            title = tab.title,
                            previewBitmapArray = tab.previewBitmapArray,
                        )
                    }
            }
            TabsScreenUiState(
                callbacks = callbacks,
                loadingState = if (state.activeGroupIndex == null) {
                    TabsScreenUiState.LoadingState.Loading
                } else {
                    TabsScreenUiState.LoadingState.Loaded(
                        groupedTabs = groupedTabs,
                        groups = groups,
                        // グループが空になる場合も含めて有効範囲にクランプする
                        activeGroupIndex = state.activeGroupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)),
                    )
                },
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            TabsScreenUiState(
                callbacks = callbacks,
                loadingState = TabsScreenUiState.LoadingState.Loading,
            ),
        )

    interface Event {
        fun closeTab(tabId: String)
    }

    init {
        // 外部フローを ViewModelState に反映する
        viewModelScope.launch {
            tabGroupRepository.observeGroups().collect { dbGroups ->
                viewModelStateFlow.update { it.copy(dbGroups = dbGroups) }
            }
        }
        viewModelScope.launch {
            tabStore.tabStoreState.collect { tabState ->
                viewModelStateFlow.update { it.copy(tabStoreState = tabState) }
            }
        }
        viewModelScope.launch {
            tabGroupRepository.observeTabGroupAssignments().collect { assignments ->
                viewModelStateFlow.update { it.copy(assignments = assignments) }
            }
        }
        viewModelScope.launch {
            // 初回: デフォルトグループを作成する（DBが空のときのみ）
            val initialTabs = tabStore.tabStoreState.first()
            tabGroupRepository.createDefaultGroupIfEmpty(initialTabs.tabs.map { it.id })
            // createDefaultGroupIfEmpty 完了後に監視を開始することで、グループが存在しない状態で
            // 新規タブの割り当てがスキップされる競合状態を防ぐ。
            // TabGroupDao.setTabGroup は INSERT IGNORE + UPDATE を行うため、
            // TabPersistenceCoordinator が tab_state 行を作成する前でも割り当てが成功する。
            viewModelStateFlow.collect { state ->
                val allTabIds = state.tabStoreState.tabs.map { it.id }.toSet()
                val assignedTabIds = state.assignments
                    .filter { it.groupId.isNotEmpty() }
                    .map { it.tabId }
                    .toSet()
                val unassigned = allTabIds - assignedTabIds
                val activeGroup = state.groups.getOrNull(state.activeGroupIndex ?: 0)
                if (unassigned.isEmpty()) return@collect
                if (activeGroup == null) return@collect
                unassigned.forEach { tabId ->
                    tabGroupRepository.assignTabToGroupIfUnassigned(tabId, activeGroup.id)
                }
            }
        }
        viewModelScope.launch {
            // 初回: 選択中タブが属するグループを activeGroupIndex に復元する。
            // groups と assignments の両方が揃った時点で判定する。
            val initialSelectedTabId = tabStore.tabStoreState.first().selectedTabId
            if (initialSelectedTabId == null) {
                viewModelStateFlow.update { it.copy(activeGroupIndex = 0) }
            } else {
                viewModelStateFlow.first { it.groups.isNotEmpty() }
                    .let { state ->
                        val groupId = state.assignments.find { it.tabId == initialSelectedTabId }?.groupId
                        val index = if (groupId != null) state.groups.indexOfFirst { it.id.value == groupId } else -1
                        viewModelStateFlow.update { it.copy(activeGroupIndex = if (index >= 0) index else 0) }
                    }
            }
            // 初回復元後、selectedTabId の変化を継続的に監視して activeGroupIndex を同期する。
            // 外部リンク等で別グループにタブが追加された場合にも、
            // タブ一覧画面を開く前に正しいグループが設定される。
            tabStore.tabStoreState.map { it.selectedTabId }.collect { selectedTabId ->
                if (selectedTabId == null) return@collect
                val state = viewModelStateFlow.value
                val groups = state.groups
                if (groups.isEmpty()) return@collect
                val assignments = tabGroupRepository.observeTabGroupAssignments().first()
                val groupId = assignments.find { it.tabId == selectedTabId }?.groupId
                val index = if (groupId != null) groups.indexOfFirst { it.id.value == groupId } else -1
                val resolvedIndex = if (index >= 0) index else 0
                if (state.activeGroupIndex != resolvedIndex) {
                    programmaticScrollTarget = resolvedIndex
                    viewModelStateFlow.update { it.copy(activeGroupIndex = resolvedIndex) }
                }
            }
        }
    }

    /**
     * グループを選択する（タブバーのタップ時）。
     * Pager のプログラム的アニメーション中に settledPage が中間ページを報告しても
     * activeGroupIndex が上書きされないよう、programmaticScrollTarget を設定する。
     */
    private fun onGroupSelected(index: Int) {
        val state = viewModelStateFlow.value
        if (state.activeGroupIndex == index) return
        val coerced = index.coerceIn(0, state.groups.lastIndex.coerceAtLeast(0))
        programmaticScrollTarget = coerced
        viewModelStateFlow.update { it.copy(activeGroupIndex = coerced) }
    }

    /**
     * ページスワイプ時にグループインデックスを同期する。
     * プログラム的アニメーション中（onGroupSelected 経由）は中間ページを無視し、
     * 目標ページに到達したときのみターゲットをクリアする。
     */
    private fun onGroupPageChanged(page: Int) {
        val target = programmaticScrollTarget
        if (target != null) {
            if (page == target) {
                programmaticScrollTarget = null
            }
            // プログラム的スクロール中は activeGroupIndex を上書きしない
            return
        }
        val state = viewModelStateFlow.value
        if (state.activeGroupIndex == page) return
        viewModelStateFlow.update {
            it.copy(activeGroupIndex = page.coerceIn(0, it.groups.lastIndex.coerceAtLeast(0)))
        }
    }

    /** 新しいグループを追加する */
    private fun addGroup() {
        viewModelScope.launch {
            val currentGroups = viewModelStateFlow.value.groups
            val newSortOrder = currentGroups.size
            val name = "グループ ${newSortOrder + 1}"
            val newId = tabGroupRepository.addGroup(name, newSortOrder)
            // Pager がアニメーション中に settledPage の中間値で activeGroupIndex を上書きしないよう
            // onGroupSelected と同様に programmaticScrollTarget を設定する
            programmaticScrollTarget = newSortOrder
            viewModelStateFlow.update {
                it.copy(
                    // ローカル順序に追加してすぐに反映する
                    localGroupOrder = currentGroups + TabGroupData(newId, name),
                    activeGroupIndex = newSortOrder,
                )
            }
        }
    }

    /**
     * グループを長押しドラッグで並び替える。
     * ローカル順序をすぐに更新して UI を即時反映し、DB へも非同期で保存する。
     */
    private fun reorderGroups(fromIndex: Int, toIndex: Int) {
        val state = viewModelStateFlow.value
        val currentGroups = state.groups.toMutableList()
        if (fromIndex !in currentGroups.indices || toIndex !in currentGroups.indices) return
        currentGroups.add(toIndex, currentGroups.removeAt(fromIndex))

        // アクティブグループのインデックスを並び替えに合わせて補正する
        val active = state.activeGroupIndex ?: 0
        val newActiveIndex = when {
            active == fromIndex -> toIndex
            fromIndex < toIndex && active in (fromIndex + 1)..toIndex -> active - 1
            fromIndex > toIndex && active in toIndex until fromIndex -> active + 1
            else -> active
        }

        viewModelStateFlow.update {
            it.copy(
                localGroupOrder = currentGroups,
                activeGroupIndex = newActiveIndex,
            )
        }

        viewModelScope.launch {
            tabGroupRepository.reorderGroups(currentGroups.map { it.id.value })
        }
    }

    /** タブが閉じられたときにグループ割り当てを解除する */
    private fun onTabClosed(tabId: String) {
        viewModelScope.launch {
            tabGroupRepository.removeTabFromGroup(tabId)
        }
    }

    /** タブを別のグループへ移動する */
    private fun moveTabToGroup(tabId: String, targetGroupIndex: Int) {
        val targetGroup = viewModelStateFlow.value.groups.getOrNull(targetGroupIndex) ?: return
        viewModelScope.launch {
            tabGroupRepository.assignTabToGroup(tabId, targetGroup.id)
        }
    }

    /**
     * グループ内でタブを並び替える。
     * グローバルリストはグループ順に連結した順序で同期する。
     */
    private fun reorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) {
        val state = viewModelStateFlow.value
        val assignmentMap = state.assignments.associate { it.tabId to it.groupId }
        val currentGroupedTabs = state.groups.map { group ->
            state.tabStoreState.tabs
                .filter { assignmentMap[it.id] == group.id.value }
                .map { tab ->
                    TabsScreenTabData(
                        id = tab.id,
                        title = tab.title,
                        previewBitmapArray = tab.previewBitmapArray,
                    )
                }
        }
        val tabsInGroup = currentGroupedTabs.getOrNull(groupIndex) ?: return
        if (fromLocalIndex !in tabsInGroup.indices || toLocalIndex !in tabsInGroup.indices) return
        val reordered = tabsInGroup.toMutableList().also {
            it.add(toLocalIndex, it.removeAt(fromLocalIndex))
        }
        // グローバルリストをグループ連結順で再構築する
        val globalOrder = currentGroupedTabs.flatMapIndexed { idx, tabs ->
            if (idx == groupIndex) reordered else tabs
        }
        // moveTab 後に tabStoreState が更新されるため、各イテレーションで最新の状態を再取得する
        globalOrder.forEachIndexed { targetIdx, tab ->
            val currentIdx = tabStore.tabStoreState.value.tabs.indexOfFirst { it.id == tab.id }
            if (currentIdx >= 0 && currentIdx != targetIdx) {
                tabStore.moveTab(currentIdx, targetIdx)
            }
        }
    }

    /**
     * グループのデフォルト設定をトグルする。
     * ON にした場合は他のグループのデフォルトをすべて解除する。
     */
    private fun toggleDefaultGroup(groupIndex: Int) {
        val currentGroups = viewModelStateFlow.value.groups
        val group = currentGroups.getOrNull(groupIndex) ?: return
        val newIsDefault = !group.isDefault
        // ローカル順序を即座に更新して UI に反映する
        val newLocalOrder = currentGroups.map { g ->
            when {
                g.id == group.id -> g.copy(isDefault = newIsDefault)
                newIsDefault -> g.copy(isDefault = false) // 他のグループのデフォルトを解除
                else -> g
            }
        }
        viewModelStateFlow.update { it.copy(localGroupOrder = newLocalOrder) }
        viewModelScope.launch {
            tabGroupRepository.setDefaultGroup(group.id, newIsDefault)
        }
    }

    /** グループ名を変更する */
    private fun renameGroup(groupIndex: Int, newName: String) {
        val currentGroups = viewModelStateFlow.value.groups
        val group = currentGroups.getOrNull(groupIndex) ?: return
        // ローカル順序を即座に更新して UI に反映する
        val newLocalOrder = currentGroups.toMutableList().also {
            it[groupIndex] = it[groupIndex].copy(name = newName)
        }
        viewModelStateFlow.update { it.copy(localGroupOrder = newLocalOrder) }
        viewModelScope.launch {
            tabGroupRepository.renameGroup(group.id, newName)
        }
    }

    /** グループを削除する。タブは隣接グループへ再割り当てされる。 */
    private fun deleteGroup(groupIndex: Int) {
        val state = viewModelStateFlow.value
        val currentGroups = state.groups
        val group = currentGroups.getOrNull(groupIndex) ?: return
        val fallback = currentGroups.firstOrNull { it.id != group.id }
        val newGroups = currentGroups.toMutableList().also { it.removeAt(groupIndex) }
        // アクティブインデックスを新しいリストに合わせて補正する
        val active = state.activeGroupIndex ?: 0
        val newActiveIndex = when {
            active == groupIndex -> (groupIndex - 1).coerceAtLeast(0)
            active > groupIndex -> active - 1
            else -> active
        }.coerceIn(0, (newGroups.size - 1).coerceAtLeast(0))

        viewModelStateFlow.update {
            it.copy(
                localGroupOrder = newGroups,
                activeGroupIndex = newActiveIndex,
            )
        }
        viewModelScope.launch {
            tabGroupRepository.deleteGroup(group.id, fallback?.id)
        }
    }

    data class ViewModelState(
        val dbGroups: List<TabGroupData> = emptyList(),
        val localGroupOrder: List<TabGroupData>? = null,
        val activeGroupIndex: Int? = null,
        val tabStoreState: TabStoreState = TabStoreState(),
        val assignments: List<TabGroupAssignment> = emptyList(),
    ) {
        /** ドラッグ中はローカル順序を優先し、DB の更新が遅れても表示が乱れないようにする。 */
        val groups: List<TabGroupData> get() = localGroupOrder ?: dbGroups
    }
}

data class TabsScreenTabData(
    val id: String,
    val title: String,
    val previewBitmapArray: ByteArray?,
)

package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.core.TabSelectionPolicy
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabSummary
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.tab.TabGroupAssignment
import net.matsudamper.browser.ui.tabs.TabPreviewImage
import net.matsudamper.browser.ui.tabs.TabsScreenTabData
import net.matsudamper.browser.ui.tabs.TabsScreenUiState


class TabsScreenViewModel(
    private val tabStore: TabStore,
    private val tabGroupRepository: TabGroupRepository,
    private val playingTabIds: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
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
        override fun onUndoCloseTab() {
            val pending = viewModelStateFlow.value.pendingClosedTab
            viewModelStateFlow.update { it.copy(pendingClosedTab = null) }
            if (pending == null) return
            val restoredTabId = tabStore.undoCloseTab() ?: return
            // 閉鎖の永続化で行ごとグループ割当も消えているため、元のグループへ割り当て直す
            val groupId = pending.groupId
            if (groupId != null) {
                viewModelScope.launch {
                    tabGroupRepository.assignTabToGroup(restoredTabId, TabGroupId(groupId))
                }
            }
            // 閉じる時点で選択を切り替えていた場合は、選択も元のタブへ戻す
            if (pending.wasSelected) {
                eventHandler.trySend { it.selectTab(restoredTabId) }
            }
        }

        override fun onConfirmCloseTab() {
            if (viewModelStateFlow.value.pendingClosedTab == null) return
            viewModelStateFlow.update { it.copy(pendingClosedTab = null) }
            // 実際の閉鎖は onCloseTab 時点で完了している。ここでは Undo 用に保持していた
            // セッションの破棄だけを行う。タブ一覧画面から離れる際 (onDispose) にも呼ばれ、
            // その時点では eventHandler の消費側が破棄済みのため Channel は経由しない
            tabStore.confirmClosedTab()
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

    val uiState: StateFlow<TabsScreenUiState> = MutableStateFlow(
        TabsScreenUiState(
            callbacks = callbacks,
            loadingState = TabsScreenUiState.LoadingState.Loading,
        )
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                val groups = state.groups
                val assignmentMap = state.assignments.associate { it.tabId to it.groupId }
                val playingIds = state.playingTabIds
                val groupedTabs = groups.map { group ->
                    state.tabStoreState.tabs
                        .filter { assignmentMap[it.id] == group.id.value }
                        .map { tab -> buildTabData(tab, playingIds) }
                }
                val groupHasPlayingTab = groupedTabs.map { tabs ->
                    tabs.any { it.isPlaying }
                }
                val pendingClosedTab = state.pendingClosedTab?.let {
                    TabsScreenUiState.PendingClosedTab(it.tabId, it.title)
                }
                uiStateFlow.update {
                    TabsScreenUiState(
                        callbacks = callbacks,
                        pendingClosedTab = pendingClosedTab,
                        loadingState = if (state.activeGroupIndex == null) {
                            TabsScreenUiState.LoadingState.Loading
                        } else {
                            TabsScreenUiState.LoadingState.Loaded(
                                groupedTabs = groupedTabs,
                                groups = groups,
                                // グループが空になる場合も含めて有効範囲にクランプする
                                activeGroupIndex = state.activeGroupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)),
                                selectedTabId = state.tabStoreState.selectedTabId,
                                groupHasPlayingTab = groupHasPlayingTab,
                                newTabListener = object : TabsScreenUiState.LoadingState.Loaded.NewTabListener {
                                    override fun onOpenNewTab() {
                                        val group = groups.getOrNull(state.activeGroupIndex ?: 0)
                                        eventHandler.trySend { it.openNewTab(group?.id) }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }.asStateFlow()

    interface Event {
        /**
         * タブを閉じた直後に呼ばれる。ナビゲーション側の後処理
         * （表示中 Browser の差し替えや、タブが無くなった場合の新規タブ作成）を行う。
         * @param nextSelectedTabId 閉鎖後に選択されているタブの ID。タブが残っていない場合は null
         */
        fun onTabClosed(closedTabId: String, nextSelectedTabId: String?)

        /** タブ一覧を開いたまま、背後の Browser の選択タブだけを切り替える */
        fun selectTab(tabId: String)

        /** タブ一覧からタブを選択し、ブラウザ画面へ戻る */
        fun openTab(tabId: String)

        /** 現在表示中のグループに新規タブを追加する */
        fun openNewTab(currentGroupId: TabGroupId?)
    }

    init {
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
            playingTabIds.collect { playingIds ->
                viewModelStateFlow.update { it.copy(playingTabIds = playingIds) }
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
                val state = viewModelStateFlow.first { it.groups.isNotEmpty() }
                val assignments = tabGroupRepository.observeTabGroupAssignments().first()
                val groupId = assignments.find { it.tabId == initialSelectedTabId }?.groupId
                val index = if (groupId != null) state.groups.indexOfFirst { it.id.value == groupId } else -1
                viewModelStateFlow.update { it.copy(activeGroupIndex = if (index >= 0) index else 0) }
            }
            // 表示グループはユーザー操作（タップ・スワイプ・グループ追加/削除）と上記の初期復元
            // でのみ変更する。selectedTabId の変化への継続追従はタブ閉鎖確定時などに表示グループが
            // 勝手に切り替わる原因になるため行わない。タブ一覧から離れると ViewModel は破棄される
            // （NavController.selectTab がバックスタックを畳む）ので、再入時は初期復元が再実行される。
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

    /** タブを別のグループへ移動する */
    private fun moveTabToGroup(tabId: String, targetGroupIndex: Int) {
        val targetGroup = viewModelStateFlow.value.groups.getOrNull(targetGroupIndex) ?: return
        viewModelScope.launch {
            tabGroupRepository.assignTabToGroup(tabId, targetGroup.id)
        }
    }

    private fun closeTab(tabId: String) {
        val state = viewModelStateFlow.value
        val storeState = state.tabStoreState.copy(
            tabGroupAssignments = state.assignments
                .filter { it.groupId.isNotEmpty() }
                .associate { it.tabId to it.groupId },
        )
        val wasSelected = storeState.selectedTabId == tabId
        val nextTabId = if (wasSelected) {
            TabSelectionPolicy.resolveNextSelectedTab(
                closingTabId = tabId,
                state = storeState,
            )
        } else {
            null
        }
        val tab = state.tabStoreState.tabs.firstOrNull { it.id == tabId }
        val title = tab?.title.orEmpty().ifBlank { tabId }
        val groupId = state.assignments
            .firstOrNull { it.tabId == tabId }
            ?.groupId
            ?.takeIf { it.isNotEmpty() }
        val nextSelectedTabId = tabStore.closeTabWithUndo(tabId, nextTabId)
        eventHandler.trySend { it.onTabClosed(tabId, nextSelectedTabId) }
        viewModelStateFlow.update {
            it.copy(
                pendingClosedTab = ViewModelState.PendingClosedTab(
                    tabId = tabId,
                    title = title,
                    wasSelected = wasSelected,
                    groupId = groupId,
                ),
            )
        }
    }

    private fun buildTabData(tab: TabSummary, playingIds: Set<String>): TabsScreenTabData {
        return TabsScreenTabData(
            id = tab.id,
            title = tab.title,
            previewImage = tab.previewBitmapArray?.let { TabPreviewImage(it) },
            isPlaying = tab.id in playingIds,
            listener = object : TabsScreenTabData.Listener {
                override fun onSelect() {
                    eventHandler.trySend { it.openTab(tab.id) }
                }

                override fun onClose() {
                    closeTab(tab.id)
                }

                override fun onMoveToGroup(targetGroupIndex: Int) {
                    moveTabToGroup(tab.id, targetGroupIndex)
                }
            },
        )
    }

    /**
     * グループ内でタブを並び替える。
     * 非同期実装の TabStore でも順序が戻らないよう、対象タブの移動を1回だけ行う。
     */
    private fun reorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) {
        val state = viewModelStateFlow.value
        val assignmentMap = state.assignments.associate { it.tabId to it.groupId }
        val group = state.groups.getOrNull(groupIndex) ?: return
        val tabsInGroup = state.tabStoreState.tabs
            .filter { assignmentMap[it.id] == group.id.value }
        if (fromLocalIndex !in tabsInGroup.indices || toLocalIndex !in tabsInGroup.indices) return
        val fromTabId = tabsInGroup[fromLocalIndex].id
        val toTabId = tabsInGroup[toLocalIndex].id
        val currentTabs = tabStore.tabStoreState.value.tabs
        val fromGlobalIndex = currentTabs.indexOfFirst { it.id == fromTabId }
        val toGlobalIndex = currentTabs.indexOfFirst { it.id == toTabId }
        if (fromGlobalIndex < 0 || toGlobalIndex < 0 || fromGlobalIndex == toGlobalIndex) return
        tabStore.moveTab(fromGlobalIndex, toGlobalIndex)
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
        val pendingClosedTab: PendingClosedTab? = null,
        val playingTabIds: Set<String> = emptySet(),
    ) {
        /** ドラッグ中はローカル順序を優先し、DB の更新が遅れても表示が乱れないようにする。 */
        val groups: List<TabGroupData> get() = localGroupOrder ?: dbGroups

        /** 閉鎖済みで Undo 可能なタブの情報（Snackbar 表示と復元に使用する） */
        data class PendingClosedTab(
            val tabId: String,
            val title: String,
            /** 閉じる時点で選択中だったか（Undo 時に選択を戻すための記録） */
            val wasSelected: Boolean,
            /** 閉じる前に属していたグループ ID（Undo 時に割り当てを復元するための記録） */
            val groupId: String?,
        )
    }
}

package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupRepository


class TabsScreenViewModel(
    private val tabStore: TabStore,
    private val tabGroupRepository: TabGroupRepository,
) : ViewModel() {

    /**
     * ドラッグ中のグループ並び順をローカルで保持する。
     * null のときは DB の順序をそのまま使う。
     */
    private val _localGroupOrder = MutableStateFlow<List<TabGroupData>?>(null)

    /** グループ一覧。ドラッグ中はローカル順序を優先し、DB の更新が遅れても表示が乱れないようにする。
     * Eagerly で収集することで、UI 購読者がいない場合（テスト等）でも groups.value が常に最新値を返す。
     */
    val groups: StateFlow<List<TabGroupData>> = combine(
        tabGroupRepository.observeGroups(),
        _localGroupOrder,
    ) { dbGroups, localOrder ->
        localOrder ?: dbGroups
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /**
     * 現在アクティブなグループのインデックス。
     * null は「復元処理が完了していない」ことを示す。
     * UI はこの値が null の間は Pager を描画しないことで、
     * 復元前の 0 で Pager が初期化されてアニメーションが走るのを防ぐ。
     */
    private val _activeGroupIndex = MutableStateFlow<Int?>(null)
    val activeGroupIndex: StateFlow<Int?> = _activeGroupIndex.asStateFlow()

    /**
     * onGroupSelected で設定したプログラム的スクロールの目標ページ。
     * Pager アニメーション中に settledPage が中間ページを報告した場合に
     * _activeGroupIndex を誤って上書きしないようにするためのガード。
     */
    private var programmaticScrollTarget: Int? = null

    /**
     * グループ別のタブリスト。
     * groups・tabStoreState・タブグループ割り当ての3つを combine して算出する。
     */
    val groupedTabs: StateFlow<List<List<TabsScreenTabData>>> = combine(
        groups,
        tabStore.tabStoreState,
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

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : TabsScreenUiState.Callbacks {
        override fun onCloseTab(tabId: String) {
            onTabClosed(tabId)
            eventHandler.trySend { it.closeTab(tabId) }
        }

        override fun onReorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) {
            reorderTabs(groupIndex, fromLocalIndex, toLocalIndex)
        }

        override fun onReorderGroups(fromIndex: Int, toIndex: Int) {
            reorderGroups(fromIndex, toIndex)
        }

        override fun onGroupSelected(index: Int) {
            onGroupSelected(index)
        }

        override fun onGroupPageChanged(page: Int) {
            onGroupPageChanged(page)
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
    }

    val uiState: StateFlow<TabsScreenUiState> = combine(
        groups,
        groupedTabs,
        activeGroupIndex,
    ) { currentGroups, currentGroupedTabs, currentActiveIndex ->
        TabsScreenUiState(
            callbacks = callbacks,
            loadingState = if (currentActiveIndex == null) {
                TabsScreenUiState.LoadingState.Loading
            } else {
                TabsScreenUiState.LoadingState.Loaded(
                    groupedTabs = currentGroupedTabs,
                    groups = currentGroups,
                    activeGroupIndex = currentActiveIndex,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TabsScreenUiState(
            callbacks = callbacks,
            loadingState = TabsScreenUiState.LoadingState.Loading,
        ),
    )

    interface Event {
        fun closeTab(tabId: String)
    }

    init {
        viewModelScope.launch {
            // 初回: デフォルトグループを作成する（DBが空のときのみ）
            val initialTabs = tabStore.tabStoreState.first()
            tabGroupRepository.createDefaultGroupIfEmpty(initialTabs.tabs.map { it.id })
            // createDefaultGroupIfEmpty 完了後に監視を開始することで、グループが存在しない状態で
            // 新規タブの割り当てがスキップされる競合状態を防ぐ。
            // TabGroupDao.setTabGroup は INSERT IGNORE + UPDATE を行うため、
            // TabPersistenceCoordinator が tab_state 行を作成する前でも割り当てが成功する。
            combine(
                tabStore.tabStoreState.map { state -> state.tabs.map { it.id }.toSet() },
                tabGroupRepository.observeTabGroupAssignments(),
                groups,
                _activeGroupIndex,
            ) { allTabIds, assignments, groupList, activeIndex ->
                val assignedTabIds = assignments
                    .filter { it.groupId.isNotEmpty() }
                    .map { it.tabId }
                    .toSet()
                val unassigned = allTabIds - assignedTabIds
                val activeGroup = groupList.getOrNull(activeIndex ?: 0)
                Pair(unassigned, activeGroup)
            }
                .collect { (unassignedTabIds, activeGroup) ->
                    if (unassignedTabIds.isEmpty()) return@collect
                    if (activeGroup == null) return@collect
                    unassignedTabIds.forEach { tabId ->
                        tabGroupRepository.assignTabToGroup(tabId, activeGroup.id)
                    }
                }
        }
        viewModelScope.launch {
            // アプリ再起動後に選択中タブが属するグループを activeGroupIndex に復元する。
            // groups と assignments の両方が揃った時点で一度だけ判定する。
            // selectedTabId が null（新規起動等）の場合は 0 を設定して即座に Pager を表示できるようにする。
            val selectedTabId = tabStore.tabStoreState.first().selectedTabId
            if (selectedTabId == null) {
                _activeGroupIndex.value = 0
                return@launch
            }
            combine(
                groups,
                tabGroupRepository.observeTabGroupAssignments(),
            ) { groupList, assignments ->
                Pair(groupList, assignments)
            }.first { (groupList, _) -> groupList.isNotEmpty() }
                .let { (groupList, assignments) ->
                    val groupId = assignments.find { it.tabId == selectedTabId }?.groupId
                    val index = if (groupId != null) groupList.indexOfFirst { it.id.value == groupId } else -1
                    _activeGroupIndex.value = if (index >= 0) index else 0
                }
        }
    }

    /**
     * グループを選択する（タブバーのタップ時）。
     * Pager のプログラム的アニメーション中に settledPage が中間ページを報告しても
     * _activeGroupIndex が上書きされないよう、programmaticScrollTarget を設定する。
     */
    fun onGroupSelected(index: Int) {
        if (_activeGroupIndex.value == index) return
        val coerced = index.coerceIn(0, groups.value.lastIndex.coerceAtLeast(0))
        programmaticScrollTarget = coerced
        _activeGroupIndex.value = coerced
    }

    /**
     * ページスワイプ時にグループインデックスを同期する。
     * プログラム的アニメーション中（onGroupSelected 経由）は中間ページを無視し、
     * 目標ページに到達したときのみターゲットをクリアする。
     */
    fun onGroupPageChanged(page: Int) {
        val target = programmaticScrollTarget
        if (target != null) {
            if (page == target) {
                // アニメーションが目標ページに到達 → ターゲットをクリア
                programmaticScrollTarget = null
            }
            // プログラム的スクロール中は _activeGroupIndex を上書きしない
            return
        }
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
            // Pager がアニメーション中に settledPage の中間値で _activeGroupIndex を上書きしないよう
            // onGroupSelected と同様に programmaticScrollTarget を設定する
            programmaticScrollTarget = newSortOrder
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
        val active = _activeGroupIndex.value ?: 0
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

    /** タブを別のグループへ移動する */
    fun moveTabToGroup(tabId: String, targetGroupIndex: Int) {
        val targetGroup = groups.value.getOrNull(targetGroupIndex) ?: return
        viewModelScope.launch {
            tabGroupRepository.assignTabToGroup(tabId, targetGroup.id)
        }
    }

    /**
     * グループ内でタブを並び替える。
     * グローバルリストはグループ順に連結した順序で同期する。
     */
    fun reorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int) {
        val currentGroupedTabs = groupedTabs.value
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

    /** グループ名を変更する */
    fun renameGroup(groupIndex: Int, newName: String) {
        val currentGroups = groups.value
        val group = currentGroups.getOrNull(groupIndex) ?: return
        // ローカル順序を即座に更新して UI に反映する
        _localGroupOrder.value = currentGroups.toMutableList().also {
            it[groupIndex] = it[groupIndex].copy(name = newName)
        }
        viewModelScope.launch {
            tabGroupRepository.renameGroup(group.id, newName)
        }
    }

    /** グループを削除する。タブは隣接グループへ再割り当てされる。 */
    fun deleteGroup(groupIndex: Int) {
        val currentGroups = groups.value
        val group = currentGroups.getOrNull(groupIndex) ?: return
        val fallback = currentGroups.firstOrNull { it.id != group.id }
        val newGroups = currentGroups.toMutableList().also { it.removeAt(groupIndex) }
        _localGroupOrder.value = newGroups
        // アクティブインデックスを新しいリストに合わせて補正する
        val active = _activeGroupIndex.value ?: 0
        _activeGroupIndex.value = when {
            active == groupIndex -> (groupIndex - 1).coerceAtLeast(0)
            active > groupIndex -> active - 1
            else -> active
        }.coerceIn(0, (newGroups.size - 1).coerceAtLeast(0))
        viewModelScope.launch {
            tabGroupRepository.deleteGroup(group.id, fallback?.id)
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

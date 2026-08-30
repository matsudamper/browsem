package net.matsudamper.browser.screen.tab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.matsudamper.browser.core.TabSelectionPolicy
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.core.TabSummary
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.tab.TabGroupAssignment
import net.matsudamper.browser.ui.tabs.TabsScreenUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabsScreenViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------

    private class FakeTabStore : TabStore {
        private val _state = MutableStateFlow(TabStoreState())
        override val tabStoreState: StateFlow<TabStoreState> = _state

        // 記録用
        val closedTabIds = mutableListOf<String>()
        val confirmedTabIds = mutableListOf<String>()
        private var detachedTab: Pair<TabSummary, Int>? = null

        override fun closeTab(tabId: String): String? {
            val result = closeTabWithUndo(tabId, nextSelectedTabId = null)
            confirmClosedTab()
            return result
        }

        override fun closeTabWithUndo(tabId: String, nextSelectedTabId: String?): String? {
            confirmClosedTab()
            val state = _state.value
            val index = state.tabs.indexOfFirst { it.id == tabId }
            if (index < 0) return state.selectedTabId
            closedTabIds += tabId
            detachedTab = state.tabs[index] to index
            val resolvedNext = nextSelectedTabId
                ?: if (state.selectedTabId == tabId) {
                    TabSelectionPolicy.resolveNextSelectedTab(closingTabId = tabId, state = state)
                } else {
                    state.selectedTabId
                }
            _state.update { s ->
                s.copy(
                    tabs = s.tabs.filterNot { it.id == tabId },
                    selectedTabId = resolvedNext,
                )
            }
            return _state.value.selectedTabId
        }

        override fun undoCloseTab(): String? {
            val (tab, index) = detachedTab ?: return null
            detachedTab = null
            _state.update { s ->
                val tabs = s.tabs.toMutableList()
                tabs.add(index.coerceIn(0, tabs.size), tab)
                s.copy(tabs = tabs)
            }
            return tab.id
        }

        override fun confirmClosedTab() {
            val (tab, _) = detachedTab ?: return
            detachedTab = null
            confirmedTabIds += tab.id
        }

        override fun moveTab(fromIndex: Int, toIndex: Int) {
            _state.update { state ->
                val tabs = state.tabs.toMutableList()
                tabs.add(toIndex, tabs.removeAt(fromIndex))
                state.copy(tabs = tabs)
            }
        }

        fun addTab(id: String, title: String = id) {
            _state.update { state ->
                state.copy(tabs = state.tabs + TabSummary(id = id, title = title, url = "https://example.com"))
            }
        }

        fun setSelectedTabId(tabId: String?) {
            _state.update { it.copy(selectedTabId = tabId) }
        }

    }

    private class FakeLaggyTabStore : TabStore {
        private val _state = MutableStateFlow(TabStoreState())
        override val tabStoreState: StateFlow<TabStoreState> = _state

        val moveRequests = mutableListOf<Pair<Int, Int>>()

        override fun moveTab(fromIndex: Int, toIndex: Int) {
            moveRequests += fromIndex to toIndex
        }

        override fun closeTab(tabId: String): String? {
            return _state.value.selectedTabId
        }

        override fun closeTabWithUndo(tabId: String, nextSelectedTabId: String?): String? {
            return _state.value.selectedTabId
        }

        override fun undoCloseTab(): String? = null

        override fun confirmClosedTab() = Unit

        fun addTab(id: String, title: String = id) {
            _state.update { state ->
                state.copy(tabs = state.tabs + TabSummary(id = id, title = title, url = "https://example.com"))
            }
        }
    }

    private class FakeTabGroupRepository : TabGroupRepository {
        private val groupsFlow = MutableStateFlow<List<TabGroupData>>(emptyList())
        private val assignmentsFlow = MutableStateFlow<List<TabGroupAssignment>>(emptyList())

        // 記録用
        val assignedTabs = mutableListOf<Pair<String, TabGroupId>>()

        override fun observeGroups() = groupsFlow
        override fun observeTabGroupAssignments() = assignmentsFlow

        override suspend fun createDefaultGroupIfEmpty(tabIds: List<String>): TabGroupId {
            if (groupsFlow.value.isNotEmpty()) {
                // 既存グループがある場合：groupId が空のタブのみ割り当て
                val firstGroup = groupsFlow.value.first()
                val assignedTabIds = assignmentsFlow.value
                    .filter { it.groupId.isNotEmpty() }
                    .map { it.tabId }
                    .toSet()
                val unassigned = tabIds.filter { it !in assignedTabIds }
                unassigned.forEach { assignTabToGroup(it, firstGroup.id) }
                return firstGroup.id
            }
            val id = TabGroupId("default")
            val group = TabGroupData(id, "デフォルト")
            groupsFlow.value = listOf(group)
            tabIds.forEach { assignTabToGroup(it, id) }
            return id
        }

        override suspend fun addGroup(name: String, sortOrder: Int): TabGroupId {
            val id = TabGroupId("group_$sortOrder")
            groupsFlow.update { it + TabGroupData(id, name) }
            return id
        }

        override suspend fun assignTabToGroup(tabId: String, groupId: TabGroupId) {
            assignedTabs += tabId to groupId
            assignmentsFlow.update { current ->
                val filtered = current.filter { it.tabId != tabId }
                filtered + TabGroupAssignment(tabId = tabId, groupId = groupId.value)
            }
        }

        override suspend fun removeTabFromGroup(tabId: String) {
            assignmentsFlow.update { current -> current.filter { it.tabId != tabId } }
        }

        override suspend fun reorderGroups(orderedGroupIds: List<String>) {
            val currentGroups = groupsFlow.value
            groupsFlow.value = orderedGroupIds.mapNotNull { id ->
                currentGroups.firstOrNull { it.id.value == id }
            }
        }

        override suspend fun renameGroup(groupId: TabGroupId, name: String) {
            groupsFlow.update { groups -> groups.map { if (it.id == groupId) it.copy(name = name) else it } }
        }

        override suspend fun deleteGroup(groupId: TabGroupId, fallbackGroupId: TabGroupId?) {
            if (fallbackGroupId != null) {
                assignmentsFlow.update { assignments ->
                    assignments.map { if (it.groupId == groupId.value) it.copy(groupId = fallbackGroupId.value) else it }
                }
            }
            groupsFlow.update { groups -> groups.filter { it.id != groupId } }
        }

        override suspend fun setDefaultGroup(groupId: TabGroupId, isDefault: Boolean) {
            groupsFlow.update { groups ->
                groups.map { group ->
                    when {
                        isDefault -> group.copy(isDefault = group.id == groupId)
                        group.id == groupId -> group.copy(isDefault = false)
                        else -> group
                    }
                }
            }
        }

        override suspend fun getDefaultGroupId(): TabGroupId? {
            return groupsFlow.value.firstOrNull { it.isDefault }?.id
        }

        override suspend fun assignTabToGroupIfUnassigned(tabId: String, groupId: TabGroupId) {
            val current = assignmentsFlow.value.find { it.tabId == tabId }
            if (current == null || current.groupId.isEmpty()) {
                assignTabToGroup(tabId, groupId)
            }
        }

        fun setGroups(groups: List<TabGroupData>) {
            groupsFlow.value = groups
        }
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    private fun buildViewModel(
        tabStore: FakeTabStore,
        repo: FakeTabGroupRepository,
        scope: TestScope,
    ): TabsScreenViewModel {
        return TabsScreenViewModel(
            tabStore = tabStore,
            tabGroupRepository = repo,
        )
    }

    /**
     * uiState から activeGroupIndex を取得するヘルパー。
     * Loaded 状態でなければ null を返す。
     */
    private fun TabsScreenViewModel.activeGroupIndexFromUiState(): Int? {
        return (uiState.value.loadingState as? TabsScreenUiState.LoadingState.Loaded)?.activeGroupIndex
    }

    private fun TabsScreenViewModel.closeTabById(tabId: String) {
        val loaded = uiState.value.loadingState as TabsScreenUiState.LoadingState.Loaded
        loaded.groupedTabs
            .flatten()
            .first { it.id == tabId }
            .onClose()
    }

    // -----------------------------------------------------------------------
    // バグ2 再現テスト
    // -----------------------------------------------------------------------

    /**
     * 再現シナリオ:
     * 1. グループが3つある（左・中・右）
     * 2. 3番目（右）のグループを選択（activeGroupIndex = 2）
     * 3. 新規タブを追加（tabStoreState に新しいタブが追加される）
     * 4. 新規タブが「右」グループに割り当てられること
     *
     * 旧実装のバグ: tabStoreState 変化時点では DB に行がないため assignTabToGroup の UPDATE が
     * ヒットせず、syncTabs 後に groupId="" で行が作られ、再起動時に先頭グループに再割り当てされた。
     */
    @Test
    fun newTab_isAssignedToActiveGroup_whenActiveGroupIsLast() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        // グループを3つ設定
        val groupLeft = TabGroupData(TabGroupId("g1"), "左")
        val groupMiddle = TabGroupData(TabGroupId("g2"), "中")
        val groupRight = TabGroupData(TabGroupId("g3"), "右")
        repo.setGroups(listOf(groupLeft, groupMiddle, groupRight))

        val viewModel = buildViewModel(tabStore, repo, this)

        // createDefaultGroupIfEmpty を完了させる
        advanceUntilIdle()

        // 右グループ（index=2）を選択
        viewModel.uiState.value.callbacks.onGroupSelected(2)

        // 新規タブを追加（tabStoreState に行が追加 → DB はまだ空）
        tabStore.addTab("tab-new")

        // Fake では DB に即時反映されるので、未割当タブが検出されて assignTabToGroup が呼ばれるはず
        advanceUntilIdle()

        // 新規タブが右グループ（g3）に割り当てられていること
        val assignment = repo.assignedTabs.lastOrNull { it.first == "tab-new" }
        assertEquals(
            "新規タブは選択中のグループ（右=g3）に割り当てられるべき",
            TabGroupId("g3"),
            assignment?.second,
        )
    }

    /**
     * 再現シナリオ（1つ目のタブが割り当てられない問題）:
     * 起動時に createDefaultGroupIfEmpty が呼ばれる前に tabStoreState に既にタブがある場合、
     * そのタブが正しくグループに割り当てられること。
     */
    @Test
    fun firstTab_isAssignedToDefaultGroup_onInitialization() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        // 起動時から1つタブが存在する
        tabStore.addTab("tab-existing")

        val viewModel = buildViewModel(tabStore, repo, this)

        // createDefaultGroupIfEmpty → デフォルトグループが作られてタブが割り当てられる
        advanceUntilIdle()

        val assignment = repo.assignedTabs.firstOrNull { it.first == "tab-existing" }
        assertEquals(
            "既存タブはデフォルトグループに割り当てられるべき",
            TabGroupId("default"),
            assignment?.second,
        )
    }

    /**
     * 再現シナリオ（1つ目の新規タブが割り当てられない問題の本質）:
     * アクティブグループが設定されている状態で最初の新規タブを追加した場合、
     * そのタブがアクティブグループに割り当てられること。
     *
     * 旧実装では：
     * - tabStoreState 変化を検知 → assignTabToGroup 呼び出し
     * - DB にタブ行がまだないため UPDATE がヒットしない
     * - 1個目は失敗し、2個目以降（DB 行が存在する）は成功していた
     *
     * 新実装では：
     * - DB 書き込み後に observeTabGroupAssignments が未割当タブを検出して割り当て
     */
    @Test
    fun firstNewTab_isAssignedToActiveGroup_notSkipped() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        // グループB（index=1）を選択
        viewModel.uiState.value.callbacks.onGroupSelected(1)

        // 1つ目の新規タブを追加
        tabStore.addTab("tab-1")
        advanceUntilIdle()

        // 2つ目の新規タブを追加
        tabStore.addTab("tab-2")
        advanceUntilIdle()

        // 両方ともグループBに割り当てられていること
        val assignment1 = repo.assignedTabs.lastOrNull { it.first == "tab-1" }
        val assignment2 = repo.assignedTabs.lastOrNull { it.first == "tab-2" }

        assertEquals("1つ目の新規タブもグループBに割り当てられるべき", TabGroupId("gB"), assignment1?.second)
        assertEquals("2つ目の新規タブもグループBに割り当てられるべき", TabGroupId("gB"), assignment2?.second)
    }

    // -----------------------------------------------------------------------
    // バグ1 再現テスト: Pager アニメーション中に中間ページが報告される問題
    // -----------------------------------------------------------------------

    /**
     * 再現シナリオ:
     * 1. グループが3つある（左・中・右）
     * 2. ユーザーが右グループ（index=2）を選択 → onGroupSelected(2)
     * 3. Pager がアニメーション中に中間ページ(1)を settledPage として報告
     *    → onGroupPageChanged(1) が呼ばれる
     * 4. この状態でタブを追加しても、右グループ（index=2）に割り当てられるべき
     *
     * 旧実装のバグ: onGroupPageChanged(1) が _activeGroupIndex を 1 に上書きし、
     * 次のタブ追加が中グループに割り当てられてしまっていた。
     * 新実装: programmaticScrollTarget によりアニメーション中の中間ページは無視される。
     */
    @Test
    fun intermediatePageDuringAnimation_doesNotOverwriteActiveGroup() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupLeft = TabGroupData(TabGroupId("g1"), "左")
        val groupMiddle = TabGroupData(TabGroupId("g2"), "中")
        val groupRight = TabGroupData(TabGroupId("g3"), "右")
        repo.setGroups(listOf(groupLeft, groupMiddle, groupRight))

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        // ユーザーが右グループ（index=2）をタップ
        viewModel.uiState.value.callbacks.onGroupSelected(2)
        advanceUntilIdle()
        assertEquals("onGroupSelected 後は activeGroupIndex=2", 2, viewModel.activeGroupIndexFromUiState())

        // Pager アニメーション中に中間ページ(1) が報告される
        viewModel.uiState.value.callbacks.onGroupPageChanged(1)
        advanceUntilIdle()
        assertEquals(
            "中間ページ報告後も activeGroupIndex は 2 のまま（上書きされない）",
            2,
            viewModel.activeGroupIndexFromUiState(),
        )

        // アニメーションが目標ページ(2) に到達
        viewModel.uiState.value.callbacks.onGroupPageChanged(2)
        advanceUntilIdle()
        assertEquals("目標ページ到達後も activeGroupIndex は 2", 2, viewModel.activeGroupIndexFromUiState())

        // その後のユーザースワイプ（プログラム的でない）は通常通り反映される
        viewModel.uiState.value.callbacks.onGroupPageChanged(0)
        advanceUntilIdle()
        assertEquals("ユーザースワイプによるページ変更は反映される", 0, viewModel.activeGroupIndexFromUiState())
    }

    /**
     * 中間ページ問題＋タブ追加の統合テスト。
     * アニメーション中に中間ページが報告された後でタブを追加しても、
     * 正しいグループに割り当てられることを確認する。
     */
    @Test
    fun newTab_assignedToCorrectGroup_evenAfterIntermediatePageReport() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupLeft = TabGroupData(TabGroupId("g1"), "左")
        val groupMiddle = TabGroupData(TabGroupId("g2"), "中")
        val groupRight = TabGroupData(TabGroupId("g3"), "右")
        repo.setGroups(listOf(groupLeft, groupMiddle, groupRight))

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        // 右グループ（index=2）を選択
        viewModel.uiState.value.callbacks.onGroupSelected(2)

        // Pager アニメーション中に中間ページ(1)が報告される
        viewModel.uiState.value.callbacks.onGroupPageChanged(1)

        // この状態で新規タブを追加
        tabStore.addTab("tab-after-animation")
        advanceUntilIdle()

        // 新規タブは右グループ（g3）に割り当てられるべき（中グループ g2 ではない）
        val assignment = repo.assignedTabs.lastOrNull { it.first == "tab-after-animation" }
        assertEquals(
            "アニメーション中間ページの後でも新規タブは右グループ（g3）に割り当てられるべき",
            TabGroupId("g3"),
            assignment?.second,
        )
    }

    // -----------------------------------------------------------------------
    // 再起動後の activeGroupIndex 復元テスト
    // -----------------------------------------------------------------------

    /**
     * 再現シナリオ（本修正のターゲット）:
     * 1. 2つのグループがある（グループA=index0、グループB=index1）
     * 2. グループBのタブを選択した状態でアプリが終了（selectedTabId がグループBのタブを指す）
     * 3. アプリ再起動 → TabsScreenViewModel が生成される
     * 4. タブ画面を開いたとき activeGroupIndex が 1（グループB）になっていること
     *
     * 修正前: _activeGroupIndex が常に 0 で初期化されるためグループAが表示されていた
     * 修正後: 選択中タブのグループを検索して activeGroupIndex を復元する
     */
    @Test
    fun activeGroupIndex_isRestoredFromSelectedTab_onRestart() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))

        // グループBのタブをあらかじめ追加して選択中にする（再起動後の状態を模倣）
        tabStore.addTab("tab-in-b")
        tabStore.setSelectedTabId("tab-in-b")
        // グループBに割り当て済みとする
        repo.assignTabToGroup("tab-in-b", groupB.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        assertEquals(
            "再起動後に選択中タブ（グループB=index1）のグループに activeGroupIndex が復元されるべき",
            1,
            viewModel.activeGroupIndexFromUiState(),
        )
    }

    /**
     * 仕様: タブ一覧の表示グループはユーザー操作と初期復元でのみ変わる。
     * ViewModel 存続中（=タブ一覧表示中）に selectedTabId が変わっても、
     * activeGroupIndex は追従しない。
     * タブ一覧から離れると ViewModel は破棄されるため、再入時は初期復元で
     * 選択タブのグループが表示される。
     */
    @Test
    fun activeGroupIndex_doesNotFollowSelectedTabChange_whileScreenShown() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))

        // 最初にグループAのタブを選択した状態でViewModel作成（タブ一覧を開いた状態を模倣）
        tabStore.addTab("tab-in-a")
        tabStore.setSelectedTabId("tab-in-a")
        repo.assignTabToGroup("tab-in-a", groupA.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        assertEquals(
            "初期状態ではグループA(index=0)が選択されるべき",
            0,
            viewModel.activeGroupIndexFromUiState(),
        )

        // 表示中に裏でグループBのタブが追加・選択される
        tabStore.addTab("tab-external")
        tabStore.setSelectedTabId("tab-external")
        repo.assignTabToGroup("tab-external", groupB.id)
        advanceUntilIdle()

        assertEquals(
            "表示中は選択タブが変わっても activeGroupIndex は変わらないべき",
            0,
            viewModel.activeGroupIndexFromUiState(),
        )
    }

    /**
     * selectedTabId が null の場合（起動直後などタブがない状態）、
     * activeGroupIndex はデフォルト値 0 のまま変わらないこと。
     */
    @Test
    fun activeGroupIndex_remainsZero_whenNoSelectedTab() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))
        // selectedTabId は null のまま（FakeTabStore の初期値）

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        assertEquals(
            "selectedTabId が null の場合 activeGroupIndex は 0 のまま",
            0,
            viewModel.activeGroupIndexFromUiState(),
        )
    }

    // -----------------------------------------------------------------------
    // バグ再現テスト: 別グループのタブを閉じると activeGroupIndex が変わる問題
    // -----------------------------------------------------------------------

    /**
     * 再現シナリオ:
     * 1. グループAにアクティブなタブ（tab-a）がある
     * 2. グループBにタブ（tab-b）がある（選択されていない）
     * 3. ユーザーがグループBを表示中（activeGroupIndex = 1）
     * 4. グループBのタブ（tab-b）を閉じる
     * 5. 期待: activeGroupIndex は 1 のまま（グループBにとどまる）
     *
     * バグ: tabStoreState が emit されると selectedTabId の collect が再発火し、
     * selectedTabId="tab-a" → グループA(index=0) と再計算して
     * activeGroupIndex を 0 に上書きしてしまう。
     */
    @Test
    fun closingTabInOtherGroup_doesNotJumpToActiveTabGroup() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))

        // グループAのタブを追加・選択（アクティブタブ）
        tabStore.addTab("tab-a")
        tabStore.setSelectedTabId("tab-a")
        repo.assignTabToGroup("tab-a", groupA.id)

        // グループBのタブを追加（選択はしない）
        tabStore.addTab("tab-b")
        repo.assignTabToGroup("tab-b", groupB.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        // 初期状態: アクティブタブがグループA → activeGroupIndex = 0
        assertEquals("初期状態はグループA（index=0）", 0, viewModel.activeGroupIndexFromUiState())

        // ユーザーがグループBを選択して表示する
        viewModel.uiState.value.callbacks.onGroupSelected(1)
        advanceUntilIdle()
        assertEquals("グループBを選択後は activeGroupIndex = 1", 1, viewModel.activeGroupIndexFromUiState())

        // グループBのタブ（tab-b）を閉じる（選択中ではないため selectedTabId は "tab-a" のまま）
        viewModel.closeTabById("tab-b")
        advanceUntilIdle()

        // 期待: グループBのままでいるべき（activeGroupIndex = 1）
        // バグ: tabStoreState の emit で collect が再発火し、
        //        selectedTabId="tab-a" → グループA(index=0) に戻ってしまう
        assertEquals(
            "アクティブでないグループのタブを閉じても activeGroupIndex は変わらないべき",
            1,
            viewModel.activeGroupIndexFromUiState(),
        )
    }

    // -----------------------------------------------------------------------
    // タブ閉鎖時の選択切替と表示グループ維持のテスト
    // -----------------------------------------------------------------------

    /** eventHandler 経由のイベントを記録する Fake */
    private class RecordingEvent : TabsScreenViewModel.Event {
        val closedTabIds = mutableListOf<String>()
        val selectedTabIds = mutableListOf<String>()

        override fun onTabClosed(closedTabId: String, nextSelectedTabId: String?) {
            closedTabIds += closedTabId
        }

        override fun selectTab(tabId: String) {
            selectedTabIds += tabId
        }

        override fun openNewTab(currentGroupId: TabGroupId?) = Unit
    }

    /** eventHandler に溜まったイベントをすべて recorder へ流す */
    private fun TabsScreenViewModel.drainEvents(recorder: RecordingEvent) {
        while (true) {
            val action = eventHandler.tryReceive().getOrNull() ?: break
            action(recorder)
        }
    }

    /**
     * 仕様: 選択中タブを閉じたら、確定（Snackbar 消滅）を待たずにその時点で実際に閉じ、
     * 次のタブへ選択を切り替える。確定時には選択タブが変わらないため、
     * 別グループを表示していても表示グループが勝手に戻らない。
     *
     * 再現シナリオ（元バグ）:
     * 1. グループA のアクティブタブを閉じる
     * 2. グループB（index=1）へ表示を切り替える
     * 3. Snackbar 消滅で確定 → 旧実装では選択タブがグループA のタブへ変わり、
     *    同期処理が表示をグループA へ戻していた
     */
    @Test
    fun closingSelectedTab_switchesSelectionImmediately_andGroupStaysAfterConfirm() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()
        val recorder = RecordingEvent()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))

        tabStore.addTab("tab-a1")
        tabStore.addTab("tab-a2")
        tabStore.addTab("tab-b")
        tabStore.setSelectedTabId("tab-a1")
        repo.assignTabToGroup("tab-a1", groupA.id)
        repo.assignTabToGroup("tab-a2", groupA.id)
        repo.assignTabToGroup("tab-b", groupB.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()
        assertEquals("初期表示は選択タブのグループA", 0, viewModel.activeGroupIndexFromUiState())

        // 選択中タブを閉じる → この時点で実際に閉じ、次のタブ（同グループの tab-a2）へ選択切替
        viewModel.closeTabById("tab-a1")
        viewModel.drainEvents(recorder)
        assertEquals("閉じた時点で実際にタブが閉じられるべき", listOf("tab-a1"), tabStore.closedTabIds)
        assertEquals("閉じた時点で閉鎖イベントが発行されるべき", listOf("tab-a1"), recorder.closedTabIds)
        assertEquals(
            "選択は同グループの次タブへ切り替わるべき",
            "tab-a2",
            tabStore.tabStoreState.value.selectedTabId,
        )
        advanceUntilIdle()

        // グループBへ表示を切り替える
        viewModel.uiState.value.callbacks.onGroupSelected(1)
        advanceUntilIdle()
        assertEquals(1, viewModel.activeGroupIndexFromUiState())

        // Snackbar 消滅で確定 → セッション破棄のみで、選択タブも表示グループも動かない
        viewModel.uiState.value.callbacks.onConfirmCloseTab()
        viewModel.drainEvents(recorder)
        assertEquals("確定で保持していたセッションが破棄されるべき", listOf("tab-a1"), tabStore.confirmedTabIds)
        advanceUntilIdle()

        assertEquals(
            "確定後も表示はグループB（index=1）に留まるべき",
            1,
            viewModel.activeGroupIndexFromUiState(),
        )
    }

    /**
     * 仕様: グループ最後のタブを閉じてグループが空になっても、表示はそのグループに留まる。
     * 次の選択タブは他グループから選ばれるが、表示グループは追従しない。
     */
    @Test
    fun closingLastTabInGroup_keepsEmptyGroupDisplayed() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()
        val recorder = RecordingEvent()

        val groupA = TabGroupData(TabGroupId("gA"), "グループA")
        val groupB = TabGroupData(TabGroupId("gB"), "グループB")
        repo.setGroups(listOf(groupA, groupB))

        tabStore.addTab("tab-a")
        tabStore.addTab("tab-b")
        tabStore.setSelectedTabId("tab-a")
        repo.assignTabToGroup("tab-a", groupA.id)
        repo.assignTabToGroup("tab-b", groupB.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()
        assertEquals(0, viewModel.activeGroupIndexFromUiState())

        // グループA 最後のタブを閉じる → 選択は他グループの tab-b へ切り替わる
        viewModel.closeTabById("tab-a")
        viewModel.drainEvents(recorder)
        assertEquals("tab-b", tabStore.tabStoreState.value.selectedTabId)
        advanceUntilIdle()

        assertEquals(
            "選択が他グループへ移っても表示は空になるグループA に留まるべき",
            0,
            viewModel.activeGroupIndexFromUiState(),
        )

        // 確定後も同様
        viewModel.uiState.value.callbacks.onConfirmCloseTab()
        viewModel.drainEvents(recorder)
        advanceUntilIdle()
        assertEquals("空グループの表示が維持されるべき", 0, viewModel.activeGroupIndexFromUiState())
    }

    /**
     * 仕様: 「戻す」を押したらタブを元の位置・グループへ復元し、選択も元のタブへ戻す。
     */
    @Test
    fun undoCloseTab_restoresTabAndSelection() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()
        val recorder = RecordingEvent()

        val group = TabGroupData(TabGroupId("g1"), "グループ1")
        repo.setGroups(listOf(group))

        tabStore.addTab("tab-1")
        tabStore.addTab("tab-2")
        tabStore.setSelectedTabId("tab-1")
        repo.assignTabToGroup("tab-1", group.id)
        repo.assignTabToGroup("tab-2", group.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        // 閉じた時点で実際に閉じられ、選択は次のタブへ切り替わる
        viewModel.closeTabById("tab-1")
        viewModel.drainEvents(recorder)
        assertEquals(listOf("tab-1"), tabStore.closedTabIds)
        assertEquals("tab-2", tabStore.tabStoreState.value.selectedTabId)
        advanceUntilIdle()

        // 「戻す」→ タブが復元され、元のタブへ選択を戻すイベントが発行される
        viewModel.uiState.value.callbacks.onUndoCloseTab()
        viewModel.drainEvents(recorder)
        advanceUntilIdle()
        assertTrue(
            "Undo でタブが復元されるべき",
            tabStore.tabStoreState.value.tabs.any { it.id == "tab-1" },
        )
        assertEquals(
            "Undo で元のタブへの選択切替イベントが発行されるべき",
            listOf("tab-1"),
            recorder.selectedTabIds,
        )
        assertTrue("Undo ではセッションは破棄されない", tabStore.confirmedTabIds.isEmpty())
        assertEquals(
            "Undo で元のグループへ割り当てが復元されるべき",
            TabGroupId("g1"),
            repo.assignedTabs.lastOrNull { it.first == "tab-1" }?.second,
        )
    }

    /**
     * 再現シナリオ（タブを削除してもタブ一覧を開き直すと復元されるバグ）:
     * 1. タブを閉じる（この時点で実際に閉じられ、タブ数も即時に減る）
     * 2. Snackbar の確定を待たずにタブ一覧画面から離れる
     *    → onDispose で onConfirmCloseTab が呼ばれるが、その時点では
     *      eventHandler の消費側（画面の LaunchedEffect）が破棄済みで
     *      Channel に送ったイベントは処理されない
     * 3. 期待: イベントが消費されなくてもタブは閉じられたままで、
     *    保持していたセッションの破棄も確定される
     *
     * 旧実装のバグ: 閉鎖自体を eventHandler 経由で行っていたため、画面破棄時の
     * 確定でイベントが失われ、タブが閉じられないまま残っていた。
     */
    @Test
    fun closeTab_takesEffectImmediately_evenWhenEventIsNotConsumed() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        val group = TabGroupData(TabGroupId("g1"), "グループ1")
        repo.setGroups(listOf(group))

        tabStore.addTab("tab-1")
        tabStore.addTab("tab-2")
        tabStore.setSelectedTabId("tab-2")
        repo.assignTabToGroup("tab-1", group.id)
        repo.assignTabToGroup("tab-2", group.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        // タブを閉じる → eventHandler を消費しなくてもこの時点で閉じられている
        viewModel.closeTabById("tab-1")
        assertEquals("閉じた時点でタブが TabStore から閉じられるべき", listOf("tab-1"), tabStore.closedTabIds)
        assertTrue(
            "閉じたタブは TabStore に残っていないべき",
            tabStore.tabStoreState.value.tabs.none { it.id == "tab-1" },
        )

        // 画面破棄を想定して eventHandler を消費せずに確定する
        viewModel.uiState.value.callbacks.onConfirmCloseTab()
        assertEquals("確定で保持していたセッションが破棄されるべき", listOf("tab-1"), tabStore.confirmedTabIds)
    }

    /**
     * 選択していないタブを閉じた場合は選択切替イベントを発行しないこと。
     */
    @Test
    fun closingUnselectedTab_doesNotSwitchSelection() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()
        val recorder = RecordingEvent()

        val group = TabGroupData(TabGroupId("g1"), "グループ1")
        repo.setGroups(listOf(group))

        tabStore.addTab("tab-1")
        tabStore.addTab("tab-2")
        tabStore.setSelectedTabId("tab-1")
        repo.assignTabToGroup("tab-1", group.id)
        repo.assignTabToGroup("tab-2", group.id)

        val viewModel = buildViewModel(tabStore, repo, this)
        advanceUntilIdle()

        viewModel.closeTabById("tab-2")
        viewModel.drainEvents(recorder)
        assertEquals("非選択タブの閉鎖では選択は変わらない", "tab-1", tabStore.tabStoreState.value.selectedTabId)

        // Undo しても選択切替イベントは発行されない
        viewModel.uiState.value.callbacks.onUndoCloseTab()
        viewModel.drainEvents(recorder)
        assertTrue(recorder.selectedTabIds.isEmpty())
    }

    /**
     * activeGroupIndex が null（復元処理未完了）の間は Loading 状態であること。
     */
    @Test
    fun uiState_isLoading_beforeActiveGroupIndexIsRestored() = runTest(testDispatcher) {
        val tabStore = FakeTabStore()
        val repo = FakeTabGroupRepository()

        repo.setGroups(listOf(TabGroupData(TabGroupId("g1"), "グループ1")))

        val viewModel = buildViewModel(tabStore, repo, this)
        // advanceUntilIdle() を呼ばないことで、復元処理が完了していない状態を確認する
        assertTrue(
            "復元処理完了前は Loading 状態であるべき",
            viewModel.uiState.value.loadingState is TabsScreenUiState.LoadingState.Loading,
        )
    }

    /**
     * グループ内並び替え時に moveTab を1回だけ発行すること。
     * 非同期 TabStore 実装でも逆順の move が連続発行されず、順序が戻らないことを保証する。
     */
    @Test
    fun reorderTabs_emitsSingleMoveRequest_forLaggyTabStore() = runTest(testDispatcher) {
        val tabStore = FakeLaggyTabStore()
        val repo = FakeTabGroupRepository()
        val group = TabGroupData(TabGroupId("g1"), "グループ1")
        repo.setGroups(listOf(group))
        tabStore.addTab("tab-a")
        tabStore.addTab("tab-b")
        tabStore.addTab("tab-c")
        repo.assignTabToGroup("tab-a", group.id)
        repo.assignTabToGroup("tab-b", group.id)
        repo.assignTabToGroup("tab-c", group.id)

        val viewModel = TabsScreenViewModel(
            tabStore = tabStore,
            tabGroupRepository = repo,
        )
        advanceUntilIdle()

        viewModel.uiState.value.callbacks.onReorderTabs(
            groupIndex = 0,
            fromLocalIndex = 0,
            toLocalIndex = 1,
        )

        assertEquals("moveTab は1回だけ呼ばれるべき", 1, tabStore.moveRequests.size)
        assertEquals("先頭タブを2番目に移動する要求が発行されるべき", 0 to 1, tabStore.moveRequests.single())
    }
}

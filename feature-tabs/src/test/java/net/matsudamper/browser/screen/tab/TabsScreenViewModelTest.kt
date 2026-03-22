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
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.core.TabSummary
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.tab.TabGroupAssignment
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals("onGroupSelected 後は activeGroupIndex=2", 2, viewModel.activeGroupIndex)

        // Pager アニメーション中に中間ページ(1) が報告される
        viewModel.uiState.value.callbacks.onGroupPageChanged(1)
        assertEquals(
            "中間ページ報告後も activeGroupIndex は 2 のまま（上書きされない）",
            2,
            viewModel.activeGroupIndex,
        )

        // アニメーションが目標ページ(2) に到達
        viewModel.uiState.value.callbacks.onGroupPageChanged(2)
        assertEquals("目標ページ到達後も activeGroupIndex は 2", 2, viewModel.activeGroupIndex)

        // その後のユーザースワイプ（プログラム的でない）は通常通り反映される
        viewModel.uiState.value.callbacks.onGroupPageChanged(0)
        assertEquals("ユーザースワイプによるページ変更は反映される", 0, viewModel.activeGroupIndex)
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
            viewModel.activeGroupIndex,
        )
    }

    /**
     * 再現シナリオ（外部リンクで開いたタブが別グループに表示される問題）:
     * 1. グループが2つある（グループA=index0、グループB=index1）
     * 2. グループAのタブを選択した状態でタブ一覧を開く（ViewModel 生成、activeGroupIndex = 0）
     * 3. 外部リンクで新規タブが作成され、グループBにプリ割り当てされ、selectedTabId が更新される
     *    （AppNavigation の処理を模倣: assignTabToGroup → createAndAppendTab → selectTab）
     * 4. タブ画面を再度開いたとき activeGroupIndex が 1（グループB）に更新されるべき
     *
     * バグ: init の復元コルーチンは selectedTabId を一度だけ読み取るため、
     *       ViewModel 存続中に selectedTabId が変わっても activeGroupIndex が追従しない。
     *       Navigation 3 で ViewModel が再利用されると、古い activeGroupIndex のまま
     *       一番左のグループが表示される。
     */
    @Test
    fun activeGroupIndex_updatesWhenSelectedTabChanges_afterInitialization() = runTest(testDispatcher) {
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
            viewModel.activeGroupIndex,
        )

        // 外部リンクで新しいタブをグループBに追加・選択する（ViewModel存続中に発生）
        tabStore.addTab("tab-external")
        tabStore.setSelectedTabId("tab-external")
        repo.assignTabToGroup("tab-external", groupB.id)
        advanceUntilIdle()

        assertEquals(
            "外部リンクで開いたタブ（グループB=index1）に activeGroupIndex が更新されるべき",
            1,
            viewModel.activeGroupIndex,
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
            viewModel.activeGroupIndex,
        )
    }
}

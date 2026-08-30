package net.matsudamper.browser.screen.browser

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.tab.TabGroupAssignment
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("RemoveRedundantBackticks", "NonAsciiCharacters")
class BrowserScreenViewModelTest {

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
    // Fake / Mock
    // -----------------------------------------------------------------------

    private class FakeTabGroupRepository : TabGroupRepository {
        val groupsFlow = MutableStateFlow<List<TabGroupData>>(emptyList())
        val assignmentsFlow = MutableStateFlow<List<TabGroupAssignment>>(emptyList())

        override fun observeGroups() = groupsFlow
        override fun observeTabGroupAssignments() = assignmentsFlow

        override suspend fun createDefaultGroupIfEmpty(tabIds: List<String>) = TabGroupId("default")
        override suspend fun addGroup(name: String, sortOrder: Int) = TabGroupId("new")
        override suspend fun assignTabToGroup(tabId: String, groupId: TabGroupId) {}
        override suspend fun assignTabToGroupIfUnassigned(tabId: String, groupId: TabGroupId) {}
        override suspend fun removeTabFromGroup(tabId: String) {}
        override suspend fun reorderGroups(orderedGroupIds: List<String>) {}
        override suspend fun renameGroup(groupId: TabGroupId, name: String) {}
        override suspend fun deleteGroup(groupId: TabGroupId, fallbackGroupId: TabGroupId?) {}
        override suspend fun setDefaultGroup(groupId: TabGroupId, isDefault: Boolean) {}
        override suspend fun getDefaultGroupId(): TabGroupId? = null

        fun setGroups(groups: List<TabGroupData>) {
            groupsFlow.value = groups
        }

        fun setAssignments(assignments: List<TabGroupAssignment>) {
            assignmentsFlow.value = assignments
        }
    }

    private fun createTab(tabId: String): BrowserTab {
        return BrowserTab(
            tabId = tabId,
            session = mockk(relaxed = true),
            openerTabId = null,
            currentUrl = "https://example.com/$tabId",
            sessionState = "",
            title = tabId,
            previewBitmap = null,
        )
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    private fun buildViewModel(
        browserTabsFlow: MutableStateFlow<List<BrowserTab>>,
        tabGroupRepository: TabGroupRepository,
        screenTabId: String,
    ): BrowserScreenViewModel {
        // HistoryRepository/SettingsRepository は Android SDK に依存しているため relaxed mock で代替
        val historyRepository = mockk<HistoryRepository>(relaxed = true) {
            every { searchSuggestions(any(), any()) } returns emptyFlow()
            every { getRecentSuggestions(any()) } returns emptyFlow()
        }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns emptyFlow()
        }
        val webSuggestionRepository = mockk<WebSuggestionRepository>(relaxed = true)

        return BrowserScreenViewModel(
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            webSuggestionRepository = webSuggestionRepository,
            tabGroupRepository = tabGroupRepository,
            browserTabsFlow = browserTabsFlow,
            screenTabId = screenTabId,
        )
    }

    // -----------------------------------------------------------------------
    // swipePreview のテスト
    // -----------------------------------------------------------------------

    @Test
    fun `同じグループ内のタブのみがswipePreviewに反映される`() = runTest(testDispatcher) {
        val tabA = createTab("a")
        val tabB = createTab("b")
        val tabC = createTab("c")
        val tabD = createTab("d")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB, tabC, tabD))
        val repo = FakeTabGroupRepository()

        // 2グループ: g1=[a,b,c], g2=[d]
        repo.setGroups(
            listOf(
                TabGroupData(TabGroupId("g1"), "グループ1"),
                TabGroupData(TabGroupId("g2"), "グループ2"),
            ),
        )
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g1"),
                TabGroupAssignment("c", "g1"),
                TabGroupAssignment("d", "g2"),
            ),
        )

        // screenTabId = "b" → g1 内で前後タブを解決
        val viewModel = buildViewModel(browserTabsFlow, repo, "b")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        // "b" の前は "a"、次は "c"（g2 の "d" は含まれない）
        assertEquals("a", uiState.swipePreview.previousTab?.tab?.tabId)
        assertEquals("c", uiState.swipePreview.nextTab?.tab?.tabId)
    }

    @Test
    fun `グループ末尾タブではnextTabがnull`() = runTest(testDispatcher) {
        val tabA = createTab("a")
        val tabB = createTab("b")
        val tabC = createTab("c")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB, tabC))
        val repo = FakeTabGroupRepository()

        repo.setGroups(
            listOf(
                TabGroupData(TabGroupId("g1"), "グループ1"),
                TabGroupData(TabGroupId("g2"), "グループ2"),
            ),
        )
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g1"),
                TabGroupAssignment("c", "g2"),
            ),
        )

        // screenTabId = "b" → g1 の末尾
        val viewModel = buildViewModel(browserTabsFlow, repo, "b")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals("a", uiState.swipePreview.previousTab?.tab?.tabId)
        // "c" は g2 に属するため nextTab に含まれない
        assertNull(uiState.swipePreview.nextTab)
    }

    @Test
    fun `グループ先頭タブではpreviousTabがnull`() = runTest(testDispatcher) {
        val tabA = createTab("a")
        val tabB = createTab("b")
        val tabC = createTab("c")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB, tabC))
        val repo = FakeTabGroupRepository()

        repo.setGroups(
            listOf(
                TabGroupData(TabGroupId("g1"), "グループ1"),
                TabGroupData(TabGroupId("g2"), "グループ2"),
            ),
        )
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g2"),
                TabGroupAssignment("c", "g2"),
            ),
        )

        // screenTabId = "b" → g2 の先頭
        val viewModel = buildViewModel(browserTabsFlow, repo, "b")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        // "a" は g1 に属するため previousTab に含まれない
        assertNull(uiState.swipePreview.previousTab)
        assertEquals("c", uiState.swipePreview.nextTab?.tab?.tabId)
    }

    @Test
    fun `グループが1タブのみの場合は前後どちらもnull`() = runTest(testDispatcher) {
        val tabA = createTab("a")
        val tabB = createTab("b")
        val tabC = createTab("c")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB, tabC))
        val repo = FakeTabGroupRepository()

        repo.setGroups(
            listOf(
                TabGroupData(TabGroupId("g1"), "グループ1"),
                TabGroupData(TabGroupId("g2"), "グループ2"),
                TabGroupData(TabGroupId("g3"), "グループ3"),
            ),
        )
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g2"),
                TabGroupAssignment("c", "g3"),
            ),
        )

        // screenTabId = "b" → g2 に1タブのみ
        val viewModel = buildViewModel(browserTabsFlow, repo, "b")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertNull(uiState.swipePreview.previousTab)
        assertNull(uiState.swipePreview.nextTab)
    }

    // -----------------------------------------------------------------------
    // groupTabCount のテスト
    // -----------------------------------------------------------------------

    @Test
    fun `groupTabCountはグループ内のタブ数を返す`() = runTest(testDispatcher) {
        val tabs = listOf(createTab("a"), createTab("b"), createTab("c"), createTab("d"))
        val browserTabsFlow = MutableStateFlow(tabs)
        val repo = FakeTabGroupRepository()

        repo.setGroups(
            listOf(
                TabGroupData(TabGroupId("g1"), "グループ1"),
                TabGroupData(TabGroupId("g2"), "グループ2"),
            ),
        )
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g1"),
                TabGroupAssignment("c", "g1"),
                TabGroupAssignment("d", "g2"),
            ),
        )

        // screenTabId = "a" → g1 に3タブ
        val viewModel = buildViewModel(browserTabsFlow, repo, "a")
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.groupTabCount)
    }

    @Test
    fun `グループ未割り当てタブではgroupTabCountがnull`() = runTest(testDispatcher) {
        val tabs = listOf(createTab("a"), createTab("b"))
        val browserTabsFlow = MutableStateFlow(tabs)
        val repo = FakeTabGroupRepository()

        repo.setGroups(listOf(TabGroupData(TabGroupId("g1"), "グループ1")))
        repo.setAssignments(listOf(TabGroupAssignment("a", "g1")))

        // screenTabId = "b" → グループ未割り当て
        val viewModel = buildViewModel(browserTabsFlow, repo, "b")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.groupTabCount)
    }

    // -----------------------------------------------------------------------
    // Flow 入力変更後の反映テスト
    // -----------------------------------------------------------------------

    @Test
    fun `browserTabsFlowの変更がuiStateに反映される`() = runTest(testDispatcher) {
        val tabA = createTab("a")
        val tabB = createTab("b")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB))
        val repo = FakeTabGroupRepository()

        repo.setGroups(listOf(TabGroupData(TabGroupId("g1"), "グループ1")))
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g1"),
            ),
        )

        val viewModel = buildViewModel(browserTabsFlow, repo, "a")
        advanceUntilIdle()

        // 初期状態: a の次は b
        assertEquals("b", viewModel.uiState.value.swipePreview.nextTab?.tab?.tabId)

        // タブ c を追加し、グループに割り当て
        val tabC = createTab("c")
        browserTabsFlow.value = listOf(tabA, tabB, tabC)
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g1"),
                TabGroupAssignment("c", "g1"),
            ),
        )
        advanceUntilIdle()

        // groupTabCount が 3 に更新される
        assertEquals(3, viewModel.uiState.value.groupTabCount)
    }

    @Test
    fun `tabGroupAssignmentsの変更がswipePreviewに反映される`() = runTest(testDispatcher) {
        val tabA = createTab("a")
        val tabB = createTab("b")
        val tabC = createTab("c")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB, tabC))
        val repo = FakeTabGroupRepository()

        repo.setGroups(
            listOf(
                TabGroupData(TabGroupId("g1"), "グループ1"),
                TabGroupData(TabGroupId("g2"), "グループ2"),
            ),
        )
        // 初期: すべて g1
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g1"),
                TabGroupAssignment("c", "g1"),
            ),
        )

        val viewModel = buildViewModel(browserTabsFlow, repo, "a")
        advanceUntilIdle()

        assertEquals("b", viewModel.uiState.value.swipePreview.nextTab?.tab?.tabId)
        assertEquals(3, viewModel.uiState.value.groupTabCount)

        // b, c を g2 に移動
        repo.setAssignments(
            listOf(
                TabGroupAssignment("a", "g1"),
                TabGroupAssignment("b", "g2"),
                TabGroupAssignment("c", "g2"),
            ),
        )
        advanceUntilIdle()

        // a は g1 内で唯一のタブ → next/previous ともに null
        assertNull(viewModel.uiState.value.swipePreview.nextTab)
        assertNull(viewModel.uiState.value.swipePreview.previousTab)
        assertEquals(1, viewModel.uiState.value.groupTabCount)
    }

    // -----------------------------------------------------------------------
    // ロード未完了時のスワイプ抑制テスト
    // -----------------------------------------------------------------------

    @Test
    fun `tabGroupAssignmentsの初回発行前はswipePreviewが空`() = runTest(testDispatcher) {
        // 実機では Room の Flow が初回値を発行する前に他の Flow が先に走り、
        // 「グループ未割り当て」と判別不能な状態でスワイプができてしまう不具合があった。
        // ここでは observeTabGroupAssignments が値を発行しないリポジトリで再現する。
        val tabA = createTab("a")
        val tabB = createTab("b")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB))
        val repo = object : TabGroupRepository by FakeTabGroupRepository() {
            override fun observeGroups() = MutableStateFlow<List<TabGroupData>>(
                listOf(TabGroupData(TabGroupId("g1"), "グループ1")),
            )
            // 値を発行しない Flow（初回ロード未完了状態）
            override fun observeTabGroupAssignments() = flow<List<TabGroupAssignment>> {
                awaitCancellation()
            }
        }

        val viewModel = buildViewModel(browserTabsFlow, repo, "a")
        advanceUntilIdle()

        // assignments が未発行のため前後タブは null。グループ間誤スワイプを防ぐ。
        assertNull(viewModel.uiState.value.swipePreview.previousTab)
        assertNull(viewModel.uiState.value.swipePreview.nextTab)
    }

    @Test
    fun `tabGroupsの初回発行前はswipePreviewが空`() = runTest(testDispatcher) {
        // observeGroups が未発行の場合も前後タブを解決しないことを検証する。
        val tabA = createTab("a")
        val tabB = createTab("b")
        val browserTabsFlow = MutableStateFlow(listOf(tabA, tabB))
        val repo = object : TabGroupRepository by FakeTabGroupRepository() {
            // 値を発行しない Flow（初回ロード未完了状態）
            override fun observeGroups() = flow<List<TabGroupData>> {
                awaitCancellation()
            }
            override fun observeTabGroupAssignments() = MutableStateFlow(
                listOf(
                    TabGroupAssignment("a", "g1"),
                    TabGroupAssignment("b", "g1"),
                ),
            )
        }

        val viewModel = buildViewModel(browserTabsFlow, repo, "a")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.swipePreview.previousTab)
        assertNull(viewModel.uiState.value.swipePreview.nextTab)
    }
}

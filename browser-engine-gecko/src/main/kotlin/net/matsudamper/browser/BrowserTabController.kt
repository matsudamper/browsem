package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.core.TabInsertionPolicy
import net.matsudamper.browser.core.TabSelectionPolicy
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabRepository
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

@Stable
class BrowserTabController(
    private val tabRepository: TabRepository,
    private val tabGroupRepository: TabGroupRepository? = null,
) : TabStore {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceCoordinator = BrowserTabPersistenceCoordinator(
        tabRepository = tabRepository,
        controllerScope = controllerScope,
    )
    // セッション中に closeTab で閉じたタブの ID を記録する。
    // NavDisplay の遷移アニメーション中に BrowserScreen が再コンポーズされても
    // getOrCreateTab がタブを再作成しないようにするためのガード。
    private val closedTabIds = mutableSetOf<String>()
    private val tabRegistry = BrowserTabRegistry()
    private val tabFactory = BrowserTabFactory(
        persistenceCoordinator = persistenceCoordinator,
        onTabStateChanged = ::onTabStateChanged,
    )
    private val _tabStoreState = MutableStateFlow(TabStoreState())
    private var repositoryObservationStarted = false

    override val tabStoreState: StateFlow<TabStoreState> = _tabStoreState.asStateFlow()

    var selectedTabId: String? by mutableStateOf(null)
        private set

    val tabs: List<BrowserTab>
        get() = tabRegistry.orderedTabs()

    fun findTab(tabId: String): BrowserTab? = tabRegistry.find(tabId)

    /** タブがこのセッション中に [closeTab] で閉じられたかどうかを返す */
    fun wasTabClosed(tabId: String): Boolean = tabId in closedTabIds

    suspend fun restoreTabs(homepageUrl: String): String {
        if (tabRegistry.isEmpty()) {
            val snapshot = withContext(Dispatchers.IO) {
                val persisted = tabRepository.loadTabs()
                RestoredTabs(
                    tabs = persisted.tabs.map { tab ->
                        RestoredTab(
                            persistedTabState = tab,
                            previewImageWebp = tabRepository.loadTabThumbnail(tab.tabId) ?: byteArrayOf(),
                        )
                    },
                    selectedTabId = persisted.selectedTabId,
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (snapshot.tabs.isEmpty()) {
                    createAndAppendInitialTab(homepageUrl, persist = false)
                } else {
                    snapshot.tabs.forEachIndexed { index, restored ->
                        val tab = createRegisteredTab(
                            tabId = restored.persistedTabState.tabId,
                            session = GeckoSession(),
                            initialUrl = restored.persistedTabState.url.ifBlank { homepageUrl },
                            sessionState = restored.persistedTabState.sessionState,
                            title = restored.persistedTabState.title,
                            previewBitmapArray = restored.previewImageWebp,
                            themeColor = restored.persistedTabState.themeColor,
                            openerTabId = restored.persistedTabState.openerTabId.ifBlank { null },
                            insertIndex = index,
                        )
                        tab.pendingSessionState =
                            restored.persistedTabState.sessionState.takeIf { it.isNotBlank() }
                    }
                    publishRuntimeState(snapshot.selectedTabId)
                }
            }
            if (snapshot.tabs.isEmpty()) {
                val initialTab = tabRegistry.firstOrNull()
                if (initialTab != null) {
                    persistenceCoordinator.persistCreatedTabNow(
                        tab = initialTab,
                        insertIndex = 0,
                        selected = true,
                    )
                }
            }
        }
        startRepositoryObservation()
        return selectedTabId ?: withContext(Dispatchers.Main.immediate) {
            createAndAppendInitialTab(homepageUrl).tabId
        }
    }

    suspend fun awaitPersistenceIdle() {
        persistenceCoordinator.awaitIdle()
    }

    suspend fun getOrCreateTab(tabId: String, homepageUrl: String): BrowserTab {
        val alreadyCreatedTab = tabRegistry.find(tabId)
        if (alreadyCreatedTab != null) return alreadyCreatedTab

        return createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
    }

    fun selectTab(tabId: String?) {
        val requestedTabId = tabId?.takeIf(tabRegistry::contains)
        if (selectedTabId == requestedTabId) {
            return
        }
        publishRuntimeState(requestedTabId)
        persistenceCoordinator.persistSelection(selectedTabId)
    }

    suspend fun createAndAppendTab(
        tabId: String = UUID.randomUUID().toString(),
        initialUrl: String,
        restoredSessionState: String? = null,
        restoredTitle: String = "",
        restoredPreviewImage: ByteArray = byteArrayOf(),
        restoredThemeColor: Int? = null,
        openerTabId: String? = null,
    ): BrowserTab {
        return withContext(Dispatchers.Main) {
            val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
            val insertIndex = tabs.size
            val tab = createRegisteredTab(
                tabId = tabId,
                session = GeckoSession(),
                initialUrl = normalizedInitialUrl,
                sessionState = restoredSessionState.orEmpty(),
                title = restoredTitle,
                previewBitmapArray = restoredPreviewImage,
                themeColor = restoredThemeColor,
                openerTabId = openerTabId,
                insertIndex = insertIndex,
            )
            tab.pendingSessionState = restoredSessionState?.takeIf { it.isNotBlank() }
            val shouldSelect = selectedTabId == null
            val nextSelectedTabId = if (shouldSelect) tab.tabId else selectedTabId
            publishRuntimeState(nextSelectedTabId)
            persistenceCoordinator.persistCreatedTab(
                tab = tab,
                insertIndex = insertIndex,
                selected = shouldSelect,
            )
            tab
        }
    }

    fun createTabForNewSession(initialUrl: String, openerTabId: String? = null): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        val insertIndex = TabInsertionPolicy.resolveInsertionIndex(
            tabIds = tabs.map { it.tabId },
            openerTabId = openerTabId,
        )
        val tab = createRegisteredTab(
            tabId = UUID.randomUUID().toString(),
            session = GeckoSession(),
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmapArray = null,
            openerTabId = openerTabId,
            insertIndex = insertIndex,
        )
        tab.pendingInitialUrl = normalizedInitialUrl
        publishRuntimeState()
        persistenceCoordinator.persistCreatedTab(
            tab = tab,
            insertIndex = insertIndex,
            selected = false,
        )
        return tab
    }

    fun createAndAppendTabWithSession(
        session: GeckoSession,
        tabId: String = UUID.randomUUID().toString(),
        initialUrl: String,
        openerTabId: String? = null,
    ): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        val insertIndex = TabInsertionPolicy.resolveInsertionIndex(
            tabIds = tabs.map { it.tabId },
            openerTabId = openerTabId,
        )
        val tab = createRegisteredTab(
            tabId = tabId,
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmapArray = null,
            openerTabId = openerTabId,
            insertIndex = insertIndex,
        )
        publishRuntimeState()
        persistenceCoordinator.persistCreatedTab(
            tab = tab,
            insertIndex = insertIndex,
            selected = false,
        )
        return tab
    }

    override fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val currentTabs = tabs
        if (fromIndex !in currentTabs.indices || toIndex !in currentTabs.indices) return
        tabRegistry.move(fromIndex, toIndex)
        publishRuntimeState()
        persistenceCoordinator.persistMoveTab(fromIndex, toIndex)
    }

    fun closeTab(tabId: String): String? {
        val nextSelectedTabId = TabSelectionPolicy.resolveNextSelectedTab(
            closingTabId = tabId,
            state = _tabStoreState.value,
        )
        val removed = tabRegistry.remove(tabId)
        if (removed == null) {
            return selectedTabId
        }
        disposeTab(removed, "タブが閉じられました: $tabId")
        closedTabIds.add(tabId)
        publishRuntimeState(nextSelectedTabId)
        persistenceCoordinator.persistClosedTab(tabId, nextSelectedTabId)
        return selectedTabId
    }

    fun close() {
        tabRegistry.values().forEach { tab ->
            disposeTab(tab, "BrowserTabController が終了しました")
        }
        tabRegistry.clear()
        selectedTabId = null
        _tabStoreState.value = TabStoreState()
        controllerScope.cancel()
    }

    private fun createAndAppendInitialTab(
        homepageUrl: String,
        persist: Boolean = true,
    ): BrowserTab {
        val tab = createRegisteredTab(
            tabId = UUID.randomUUID().toString(),
            session = GeckoSession(),
            initialUrl = homepageUrl,
            sessionState = "",
            title = homepageUrl,
            previewBitmapArray = null,
        )
        publishRuntimeState(tab.tabId)
        if (persist) {
            persistenceCoordinator.persistCreatedTab(
                tab = tab,
                insertIndex = 0,
                selected = true,
            )
        }
        return tab
    }

    private fun startRepositoryObservation() {
        if (repositoryObservationStarted) {
            return
        }
        repositoryObservationStarted = true
        if (tabGroupRepository != null) {
            controllerScope.launch {
                tabGroupRepository.observeTabGroupAssignments().collectLatest { assignments ->
                    val assignmentMap = assignments
                        .filter { it.groupId.isNotEmpty() }
                        .associate { it.tabId to it.groupId }
                    _tabStoreState.update { it.copy(tabGroupAssignments = assignmentMap) }
                }
            }
        }
    }

    private fun createRegisteredTab(
        tabId: String,
        session: GeckoSession,
        initialUrl: String,
        sessionState: String,
        title: String,
        previewBitmapArray: ByteArray?,
        themeColor: Int? = null,
        openerTabId: String? = null,
        insertIndex: Int = tabs.size,
    ): BrowserTab {
        val tab = tabFactory.createTab(
            tabId = tabId,
            session = session,
            initialUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmapArray = previewBitmapArray,
            themeColor = themeColor,
            openerTabId = openerTabId,
        )
        tabRegistry.insert(tab = tab, insertIndex = insertIndex)
        return tab
    }

    private fun onTabStateChanged() {
        publishRuntimeState()
    }

    private fun publishRuntimeState(
        selectedTabId: String? = this.selectedTabId,
    ) {
        val summaries = tabRegistry.summaries()
        val nextSelectedTabId = selectedTabId
            ?.takeIf { selectedId -> summaries.any { it.id == selectedId } }
            ?: summaries.lastOrNull()?.id
        this.selectedTabId = nextSelectedTabId
        _tabStoreState.update {
            it.copy(
                tabs = summaries,
                selectedTabId = nextSelectedTabId,
            )
        }
    }

    private fun disposeTab(tab: BrowserTab, reason: String) {
        tab.disposeSessionDelegates(CancellationException(reason))
        if (tab.session.isOpen) {
            tab.session.close()
        }
    }

    private data class RestoredTabs(
        val tabs: List<RestoredTab>,
        val selectedTabId: String?,
    )

    private data class RestoredTab(
        val persistedTabState: PersistedTabState,
        val previewImageWebp: ByteArray,
    )

}

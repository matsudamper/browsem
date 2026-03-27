package net.matsudamper.browser

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.core.TabInsertionPolicy
import net.matsudamper.browser.core.TabSelectionPolicy
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.core.TabSummary
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.PersistedTabStateContainer
import net.matsudamper.browser.data.TabRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Stable
class BrowserSessionController internal constructor(
    val browserTabController: BrowserTabController,
    val browserSessionLifecycleController: BrowserSessionLifecycleController,
) : TabStore {
    override val tabStoreState: StateFlow<TabStoreState>
        get() = browserTabController.tabStoreState

    val selectedTabId: String?
        get() = browserTabController.selectedTabId

    val tabs: List<BrowserTab>
        get() = browserTabController.tabs

    fun findTab(tabId: String): BrowserTab? = browserTabController.findTab(tabId)

    suspend fun restoreTabs(homepageUrl: String): String {
        return browserTabController.restoreTabs(homepageUrl)
    }

    suspend fun awaitPersistenceIdle() {
        browserTabController.awaitPersistenceIdle()
    }

    suspend fun getOrCreateTab(tabId: String, homepageUrl: String): BrowserTab {
        return browserTabController.getOrCreateTab(tabId, homepageUrl)
    }

    fun selectTab(tabId: String?) {
        browserTabController.selectTab(tabId)
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
        return browserTabController.createAndAppendTab(
            tabId = tabId,
            initialUrl = initialUrl,
            restoredSessionState = restoredSessionState,
            restoredTitle = restoredTitle,
            restoredPreviewImage = restoredPreviewImage,
            restoredThemeColor = restoredThemeColor,
            openerTabId = openerTabId,
        )
    }

    fun restoreSession(tab: BrowserTab) {
        browserSessionLifecycleController.restoreSession(tab)
    }

    fun createTabForNewSession(initialUrl: String, openerTabId: String? = null): BrowserTab {
        return browserTabController.createTabForNewSession(initialUrl, openerTabId)
    }

    fun createAndAppendTabWithSession(
        session: GeckoSession,
        tabId: String = UUID.randomUUID().toString(),
        initialUrl: String,
        openerTabId: String? = null,
    ): BrowserTab {
        return browserTabController.createAndAppendTabWithSession(
            session = session,
            tabId = tabId,
            initialUrl = initialUrl,
            openerTabId = openerTabId,
        )
    }

    override fun moveTab(fromIndex: Int, toIndex: Int) {
        browserTabController.moveTab(fromIndex, toIndex)
    }

    fun closeTab(tabId: String): String? {
        return browserTabController.closeTab(tabId)
    }

    fun close() {
        browserTabController.close()
    }
}

@Stable
class BrowserTabController(
    private val tabRepository: TabRepository,
) : TabStore {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceMutex = Mutex()
    private val pendingCreatedTabIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingClosedTabIds = ConcurrentHashMap.newKeySet<String>()
    private val tabRegistry = LinkedHashMap<String, BrowserTab>()
    private val _tabStoreState = MutableStateFlow(TabStoreState())
    private var repositoryObservationStarted = false

    override val tabStoreState: StateFlow<TabStoreState> = _tabStoreState.asStateFlow()

    var selectedTabId: String? by mutableStateOf(null)
        private set

    val tabs: List<BrowserTab>
        get() {
            val orderedIds = _tabStoreState.value.tabs.map { it.id }
            return orderedIds.mapNotNull { tabId -> tabRegistry[tabId] }
        }

    fun findTab(tabId: String): BrowserTab? = tabRegistry[tabId]

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
                    snapshot.tabs.forEach { restored ->
                        val tab = appendTab(
                            tabId = restored.persistedTabState.tabId,
                            session = GeckoSession(),
                            initialUrl = restored.persistedTabState.url.ifBlank { homepageUrl },
                            sessionState = restored.persistedTabState.sessionState,
                            title = restored.persistedTabState.title,
                            previewBitmapArray = restored.previewImageWebp,
                            themeColor = restored.persistedTabState.themeColor,
                            openerTabId = restored.persistedTabState.openerTabId.ifBlank { null },
                        )
                        tab.pendingSessionState =
                            restored.persistedTabState.sessionState.takeIf { it.isNotBlank() }
                    }
                    publishRepositoryState(
                        persistedTabs = snapshot.tabs.map { restored -> restored.persistedTabState },
                        selectedTabId = snapshot.selectedTabId,
                    )
                }
            }
            if (snapshot.tabs.isEmpty()) {
                val initialTab = tabRegistry.values.firstOrNull()
                if (initialTab != null) {
                    persistCreatedTabNow(
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
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {}
        }
    }

    suspend fun getOrCreateTab(tabId: String, homepageUrl: String): BrowserTab {
        val alreadyCreatedTab = tabRegistry[tabId]
        if (alreadyCreatedTab != null) return alreadyCreatedTab

        return createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
    }

    fun selectTab(tabId: String?) {
        if (selectedTabId == tabId) {
            return
        }
        selectedTabId = tabId
        enqueuePersistence {
            it.selectTab(tabId)
        }
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
            val tab = appendTab(
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
            if (shouldSelect) {
                selectedTabId = tab.tabId
            }
            persistCreatedTab(
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
        val tab = appendTab(
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
        persistCreatedTab(
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
        val tab = appendTab(
            tabId = tabId,
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmapArray = null,
            openerTabId = openerTabId,
            insertIndex = insertIndex,
        )
        persistCreatedTab(
            tab = tab,
            insertIndex = insertIndex,
            selected = false,
        )
        return tab
    }

    override fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val currentTabs = _tabStoreState.value.tabs
        if (fromIndex !in currentTabs.indices || toIndex !in currentTabs.indices) return
        enqueuePersistence {
            it.moveTab(fromIndex, toIndex)
        }
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
        if (removed.session.isOpen) {
            removed.session.close()
        }
        selectedTabId = nextSelectedTabId
        pendingClosedTabIds.add(tabId)
        pendingCreatedTabIds.remove(tabId)
        enqueuePersistence {
            try {
                it.closeTab(tabId, nextSelectedTabId)
            } finally {
                pendingClosedTabIds.remove(tabId)
            }
        }
        return selectedTabId
    }

    fun close() {
        tabRegistry.values.forEach { tab ->
            if (tab.session.isOpen) {
                tab.session.close()
            }
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
        val tab = appendTab(
            tabId = UUID.randomUUID().toString(),
            session = GeckoSession(),
            initialUrl = homepageUrl,
            sessionState = "",
            title = homepageUrl,
            previewBitmapArray = null,
        )
        selectedTabId = tab.tabId
        if (persist) {
            persistCreatedTab(
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
        controllerScope.launch {
            tabRepository.observeTabs().collectLatest { state ->
                applyRepositoryState(state)
            }
        }
    }

    private suspend fun applyRepositoryState(state: PersistedTabStateContainer) {
        val persistedTabs = state.tabs.filterNot { it.tabId in pendingClosedTabIds }
        val persistedTabIds = persistedTabs.mapTo(mutableSetOf()) { it.tabId }

        persistedTabs.forEach { persistedTab ->
            val existing = tabRegistry[persistedTab.tabId]
            if (existing != null) {
                existing.syncPersistedState(persistedTab)
            } else {
                val previewBitmap = withContext(Dispatchers.IO) {
                    tabRepository.loadTabThumbnail(persistedTab.tabId)
                }
                tabRegistry[persistedTab.tabId] = appendDetachedTab(
                    persistedTabState = persistedTab,
                    previewBitmapArray = previewBitmap,
                )
            }
        }

        val removedTabIds = tabRegistry.keys.filter { tabId ->
            tabId !in persistedTabIds && tabId !in pendingCreatedTabIds
        }
        removedTabIds.forEach { tabId ->
            tabRegistry.remove(tabId)?.let { removed ->
                if (removed.session.isOpen) {
                    removed.session.close()
                }
            }
        }

        publishRepositoryState(persistedTabs, state.selectedTabId)
    }

    private fun persistCreatedTab(
        tab: BrowserTab,
        insertIndex: Int,
        selected: Boolean,
    ) {
        pendingCreatedTabIds.add(tab.tabId)
        pendingClosedTabIds.remove(tab.tabId)
        val persistedTab = tab.toPersistedTabState()
        enqueuePersistence {
            try {
                it.createOrUpdateTab(
                    tab = persistedTab,
                    insertIndex = insertIndex,
                    selected = selected,
                )
            } finally {
                pendingCreatedTabIds.remove(tab.tabId)
            }
        }
    }

    private suspend fun persistCreatedTabNow(
        tab: BrowserTab,
        insertIndex: Int,
        selected: Boolean,
    ) {
        val persistedTab = tab.toPersistedTabState()
        pendingCreatedTabIds.add(tab.tabId)
        pendingClosedTabIds.remove(tab.tabId)
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {
                try {
                    tabRepository.createOrUpdateTab(
                        tab = persistedTab,
                        insertIndex = insertIndex,
                        selected = selected,
                    )
                } finally {
                    pendingCreatedTabIds.remove(tab.tabId)
                }
            }
        }
    }

    private fun enqueuePersistence(action: suspend (TabRepository) -> Unit) {
        controllerScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                runCatching {
                    action(tabRepository)
                }.onFailure { error ->
                    Log.e(TAG, "タブ状態の永続化に失敗しました", error)
                }
            }
        }
    }

    private fun appendDetachedTab(
        persistedTabState: PersistedTabState,
        previewBitmapArray: ByteArray?,
    ): BrowserTab {
        val tab = BrowserTab(
            tabId = persistedTabState.tabId,
            session = GeckoSession(),
            openerTabId = persistedTabState.openerTabId.ifBlank { null },
            currentUrl = persistedTabState.url,
            sessionState = persistedTabState.sessionState,
            title = persistedTabState.title.ifBlank { persistedTabState.url },
            previewBitmap = previewBitmapArray ?: byteArrayOf(),
            themeColor = persistedTabState.themeColor,
            onStateChanged = ::onTabStateChanged,
            onUrlChanged = { tabId, value ->
                enqueuePersistence { repository ->
                    repository.updateUrl(tabId, value)
                }
            },
            onSessionStateChanged = { tabId, value ->
                enqueuePersistence { repository ->
                    repository.updateSessionState(tabId, value)
                }
            },
            onTitleChanged = { tabId, value ->
                enqueuePersistence { repository ->
                    repository.updateTitle(tabId, value)
                }
            },
            onPreviewBitmapChanged = { tabId, previewBitmap ->
                controllerScope.launch(Dispatchers.IO) {
                    if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                        tabRepository.saveTabThumbnail(tabId, previewBitmap)
                    }
                }
            },
            onThemeColorChanged = { tabId, value ->
                enqueuePersistence { repository ->
                    repository.updateThemeColor(tabId, value)
                }
            },
        )
        tab.pendingSessionState = persistedTabState.sessionState.takeIf { it.isNotBlank() }
        return tab
    }

    private fun appendTab(
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
        val tab = BrowserTab(
            tabId = tabId,
            session = session,
            openerTabId = openerTabId,
            currentUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmap = previewBitmapArray ?: byteArrayOf(),
            themeColor = themeColor,
            onStateChanged = ::onTabStateChanged,
            onUrlChanged = { changedTabId, value ->
                enqueuePersistence { repository ->
                    repository.updateUrl(changedTabId, value)
                }
            },
            onSessionStateChanged = { changedTabId, value ->
                enqueuePersistence { repository ->
                    repository.updateSessionState(changedTabId, value)
                }
            },
            onTitleChanged = { changedTabId, value ->
                enqueuePersistence { repository ->
                    repository.updateTitle(changedTabId, value)
                }
            },
            onPreviewBitmapChanged = { changedTabId, previewBitmap ->
                controllerScope.launch(Dispatchers.IO) {
                    if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                        tabRepository.saveTabThumbnail(changedTabId, previewBitmap)
                    }
                }
            },
            onThemeColorChanged = { changedTabId, value ->
                enqueuePersistence { repository ->
                    repository.updateThemeColor(changedTabId, value)
                }
            },
        )
        insertTabIntoRegistry(tab = tab, insertIndex = insertIndex)
        return tab
    }

    private fun onTabStateChanged() {
        refreshVisibleTabSummaries()
    }

    private fun publishRepositoryState(
        persistedTabs: List<PersistedTabState>,
        selectedTabId: String?,
    ) {
        val summaries = persistedTabs.mapNotNull { persistedTab ->
            tabRegistry[persistedTab.tabId]?.toSummary()
        }
        val nextSelectedTabId = selectedTabId
            ?.takeIf { selectedId -> summaries.any { it.id == selectedId } }
            ?: summaries.lastOrNull()?.id
        this.selectedTabId = nextSelectedTabId
        _tabStoreState.value = TabStoreState(
            tabs = summaries,
            selectedTabId = nextSelectedTabId,
        )
    }

    private fun refreshVisibleTabSummaries() {
        _tabStoreState.update { state ->
            state.copy(
                tabs = state.tabs.mapNotNull { summary ->
                    tabRegistry[summary.id]?.toSummary()
                }
            )
        }
    }

    private fun insertTabIntoRegistry(tab: BrowserTab, insertIndex: Int) {
        val orderedTabs = tabRegistry.values.toMutableList().apply {
            removeAll { existing -> existing.tabId == tab.tabId }
        }
        val targetIndex = insertIndex.coerceIn(0, orderedTabs.size)
        orderedTabs.add(targetIndex, tab)
        rebuildTabRegistry(orderedTabs)
    }

    private fun rebuildTabRegistry(orderedTabs: List<BrowserTab>) {
        tabRegistry.clear()
        orderedTabs.forEach { tab ->
            tabRegistry[tab.tabId] = tab
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

    companion object {
        private const val TAG = "BrowserTabController"
    }
}

@Stable
class BrowserSessionLifecycleController(
    private val geckoRuntime: GeckoRuntime,
) {
    fun restoreSession(tab: BrowserTab) {
        if (tab.session.isOpen) {
            val url = tab.pendingInitialUrl
            if (url != null) {
                tab.pendingInitialUrl = null
                tab.session.loadUri(url)
            }
            return
        }
        if (tab.pendingInitialUrl != null) {
            return
        }
        tab.session.open(geckoRuntime)
        val state = tab.pendingSessionState
        if (state != null) {
            tab.pendingSessionState = null
            val parsed = GeckoSession.SessionState.fromString(state)
            if (parsed != null) {
                tab.session.restoreState(parsed)
                return
            }
        }
        tab.session.loadUri(tab.currentUrl.ifBlank { "about:blank" })
    }
}

@Stable
class BrowserTab(
    val tabId: String,
    val session: GeckoSession,
    val openerTabId: String?,
    currentUrl: String,
    sessionState: String,
    title: String,
    previewBitmap: ByteArray?,
    themeColor: Int? = null,
    private val onStateChanged: () -> Unit = {},
    private val onUrlChanged: (String, String) -> Unit = { _, _ -> },
    private val onSessionStateChanged: (String, String) -> Unit = { _, _ -> },
    private val onTitleChanged: (String, String) -> Unit = { _, _ -> },
    private val onPreviewBitmapChanged: (String, ByteArray?) -> Unit = { _, _ -> },
    private val onThemeColorChanged: (String, Int?) -> Unit = { _, _ -> },
) {
    private var suppressPersistence = false
    private var currentUrlState by mutableStateOf(currentUrl)
    private var sessionStateState by mutableStateOf(sessionState)
    private var titleState by mutableStateOf(title)
    private var previewBitmapState: ByteArray? by mutableStateOf(previewBitmap)
    private var themeColorState: Int? by mutableStateOf(themeColor)

    var currentUrl: String
        get() = currentUrlState
        set(value) {
            if (currentUrlState == value) return
            currentUrlState = value
            onStateChanged()
            if (!suppressPersistence) {
                onUrlChanged(tabId, value)
            }
        }

    var sessionState: String
        get() = sessionStateState
        set(value) {
            if (sessionStateState == value) return
            sessionStateState = value
            onStateChanged()
            if (!suppressPersistence) {
                onSessionStateChanged(tabId, value)
            }
        }

    var title: String
        get() = titleState
        set(value) {
            if (titleState == value) return
            titleState = value
            onStateChanged()
            if (!suppressPersistence) {
                onTitleChanged(tabId, value)
            }
        }

    var previewBitmap: ByteArray?
        get() = previewBitmapState
        set(value) {
            if (previewBitmapState.contentEqualsNullable(value)) return
            previewBitmapState = value
            onStateChanged()
            onPreviewBitmapChanged(tabId, value)
        }

    var themeColor: Int?
        get() = themeColorState
        set(value) {
            if (themeColorState == value) return
            themeColorState = value
            onStateChanged()
            if (!suppressPersistence) {
                onThemeColorChanged(tabId, value)
            }
        }

    // ページのfavicon（ホーム追加時のアイコンに使用、永続化は不要）
    var faviconBitmap: Bitmap? by mutableStateOf(null)

    // 未オープンタブのセッション復元情報を保持
    internal var pendingSessionState: String? by mutableStateOf(null)

    // onNewSession 経由で作成されたタブの初回ロード URL を保持。
    // GeckoView が session.open() を実行するため restoreSession では isOpen==true になるが、
    // GeckoView が target URL に自動遷移しないケースに備えて明示的に loadUri を呼ぶ。
    internal var pendingInitialUrl: String? by mutableStateOf(null)

    internal fun syncPersistedState(persistedTabState: PersistedTabState) {
        suppressPersistence = true
        try {
            currentUrl = persistedTabState.url
            sessionState = persistedTabState.sessionState
            title = persistedTabState.title.ifBlank { persistedTabState.url }
            themeColor = persistedTabState.themeColor
        } finally {
            suppressPersistence = false
        }
    }
}

private fun BrowserTab.toPersistedTabState(): PersistedTabState = PersistedTabState(
    url = currentUrl,
    sessionState = sessionState,
    title = title,
    tabId = tabId,
    openerTabId = openerTabId.orEmpty(),
    themeColor = themeColor,
)

private fun BrowserTab.toSummary(): TabSummary = TabSummary(
    id = tabId,
    title = title,
    url = currentUrl,
    openerTabId = openerTabId,
    previewBitmapArray = previewBitmap,
    themeColor = themeColor,
)

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    return when {
        this === other -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
}

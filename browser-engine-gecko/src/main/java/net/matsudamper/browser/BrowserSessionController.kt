package net.matsudamper.browser

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
class BrowserSessionController(
    private val geckoRuntime: GeckoRuntime,
    private val tabRepository: TabRepository? = null,
) : TabStore {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceMutex = Mutex()
    private val pendingCreatedTabIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingClosedTabIds = ConcurrentHashMap.newKeySet<String>()
    private val tabList = mutableStateListOf<BrowserTab>()
    private val _tabStoreState = MutableStateFlow(TabStoreState())
    private var repositoryObservationStarted = false

    override val tabStoreState: StateFlow<TabStoreState> = _tabStoreState.asStateFlow()

    var selectedTabId: String? by mutableStateOf(null)
        private set

    val tabs: List<BrowserTab>
        get() = tabList

    suspend fun restoreTabs(homepageUrl: String): String {
        if (tabRepository == null) {
            return withContext(Dispatchers.Main.immediate) {
                selectedTabId ?: createAndAppendInitialTab(homepageUrl).tabId
            }
        }
        if (tabList.isEmpty()) {
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
                    selectedTabId = snapshot.selectedTabId
                        ?.takeIf { selectedId -> tabList.any { it.tabId == selectedId } }
                        ?: tabList.lastOrNull()?.tabId
                    publishState()
                }
            }
            if (snapshot.tabs.isEmpty()) {
                val initialTab = tabList.firstOrNull()
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
        if (tabRepository == null) return
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {}
        }
    }

    suspend fun getOrCreateTab(tabId: String, homepageUrl: String): BrowserTab {
        val alreadyCreatedTab = tabList.firstOrNull { it.tabId == tabId }
        if (alreadyCreatedTab != null) return alreadyCreatedTab

        return createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
    }

    fun selectTab(tabId: String?) {
        if (selectedTabId == tabId) {
            return
        }
        selectedTabId = tabId
        publishState()
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
            val tab = appendTab(
                tabId = tabId,
                session = GeckoSession(),
                initialUrl = normalizedInitialUrl,
                sessionState = restoredSessionState.orEmpty(),
                title = restoredTitle,
                previewBitmapArray = restoredPreviewImage,
                themeColor = restoredThemeColor,
                openerTabId = openerTabId,
            )
            tab.pendingSessionState = restoredSessionState?.takeIf { it.isNotBlank() }
            val shouldSelect = selectedTabId == null
            if (shouldSelect) {
                selectedTabId = tab.tabId
                publishState()
            }
            persistCreatedTab(
                tab = tab,
                insertIndex = tabList.lastIndex,
                selected = shouldSelect,
            )
            tab
        }
    }

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

    fun createTabForNewSession(initialUrl: String, openerTabId: String? = null): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        val insertIndex = TabInsertionPolicy.resolveInsertionIndex(
            tabIds = tabList.map { it.tabId },
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
        if (!session.isOpen) {
            session.open(geckoRuntime)
        }
        val insertIndex = TabInsertionPolicy.resolveInsertionIndex(
            tabIds = tabList.map { it.tabId },
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
        if (fromIndex !in tabList.indices || toIndex !in tabList.indices) return
        tabList.add(toIndex, tabList.removeAt(fromIndex))
        publishState()
        enqueuePersistence {
            it.moveTab(fromIndex, toIndex)
        }
    }

    fun closeTab(tabId: String): String? {
        val index = tabList.indexOfFirst { it.tabId == tabId }
        if (index < 0) {
            return selectedTabId
        }
        val nextSelectedTabId = TabSelectionPolicy.resolveNextSelectedTab(
            closingTabId = tabId,
            state = _tabStoreState.value,
        )
        val removed = tabList.removeAt(index)
        if (removed.session.isOpen) {
            removed.session.close()
        }
        selectedTabId = nextSelectedTabId
        publishState()
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
        tabList.forEach { tab ->
            if (tab.session.isOpen) {
                tab.session.close()
            }
        }
        tabList.clear()
        selectedTabId = null
        publishState()
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
        publishState()
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
        if (tabRepository == null || repositoryObservationStarted) {
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
        val currentTabs = tabList.associateBy { it.tabId }.toMutableMap()
        val resolvedTabs = buildList {
            persistedTabs.forEach { persistedTab ->
                val existing = currentTabs.remove(persistedTab.tabId)
                if (existing != null) {
                    existing.syncPersistedState(persistedTab)
                    add(existing)
                } else {
                    val previewBitmap = withContext(Dispatchers.IO) {
                        tabRepository?.loadTabThumbnail(persistedTab.tabId)
                    }
                    val restoredTab = appendDetachedTab(
                        persistedTabState = persistedTab,
                        previewBitmapArray = previewBitmap,
                    )
                    add(restoredTab)
                }
            }
        }

        val pendingTabs = tabList.filter { tab ->
            tab.tabId in currentTabs &&
                tab.tabId in pendingCreatedTabIds &&
                tab.tabId !in pendingClosedTabIds
        }
        val removedTabs = currentTabs.values.filterNot { tab ->
            tab.tabId in pendingCreatedTabIds
        }

        removedTabs.forEach { removed ->
            if (removed.session.isOpen) {
                removed.session.close()
            }
        }

        val mergedTabs = buildList {
            val resolvedById = resolvedTabs.associateBy { it.tabId }.toMutableMap()
            tabList.forEach { currentTab ->
                val resolved = resolvedById.remove(currentTab.tabId)
                when {
                    resolved != null -> add(resolved)
                    currentTab in pendingTabs -> add(currentTab)
                }
            }
            persistedTabs.forEach { persistedTab ->
                resolvedById.remove(persistedTab.tabId)?.let(::add)
            }
        }

        val currentIds = tabList.map { it.tabId }
        val resolvedIds = mergedTabs.map { it.tabId }
        if (currentIds != resolvedIds) {
            tabList.clear()
            tabList.addAll(mergedTabs)
        }

        val nextSelectedTabId = state.selectedTabId
            ?.takeIf { selectedId -> mergedTabs.any { it.tabId == selectedId } }
            ?: mergedTabs.lastOrNull()?.tabId
        if (selectedTabId != nextSelectedTabId) {
            selectedTabId = nextSelectedTabId
        }
        publishState()
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
        val repository = tabRepository ?: return
        val persistedTab = tab.toPersistedTabState()
        pendingCreatedTabIds.add(tab.tabId)
        pendingClosedTabIds.remove(tab.tabId)
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {
                try {
                    repository.createOrUpdateTab(
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
        val repository = tabRepository ?: return
        controllerScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                runCatching {
                    action(repository)
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
            onStateChanged = ::publishState,
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
                val repository = tabRepository
                if (repository != null) {
                    controllerScope.launch(Dispatchers.IO) {
                        if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                            repository.saveTabThumbnail(tabId, previewBitmap)
                        }
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
        insertIndex: Int = tabList.size,
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
            onStateChanged = ::publishState,
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
                val repository = tabRepository
                if (repository != null) {
                    controllerScope.launch(Dispatchers.IO) {
                        if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                            repository.saveTabThumbnail(changedTabId, previewBitmap)
                        }
                    }
                }
            },
            onThemeColorChanged = { changedTabId, value ->
                enqueuePersistence { repository ->
                    repository.updateThemeColor(changedTabId, value)
                }
            },
        )
        tabList.add(insertIndex, tab)
        publishState()
        return tab
    }

    private fun publishState() {
        _tabStoreState.value = TabStoreState(
            tabs = tabList.map { tab -> tab.toSummary() },
            selectedTabId = selectedTabId,
        )
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
        private const val TAG = "BrowserSessionController"
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

package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.matsudamper.browser.core.TabSelectionPolicy
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import net.matsudamper.browser.core.TabSummary
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

@Stable
class BrowserSessionController(runtime: GeckoRuntime) : TabStore {
    private val geckoRuntime = runtime
    private val tabList = mutableStateListOf<BrowserTab>()
    private val _tabStoreState = MutableStateFlow(TabStoreState())
    override val tabStoreState: StateFlow<TabStoreState> = _tabStoreState.asStateFlow()

    var selectedTabId: String? by mutableStateOf(null)
        private set

    val tabs: List<BrowserTab>
        get() = tabList

    fun getOrCreateTab(tabId: String, homepageUrl: String): BrowserTab {
        val alreadyCreatedTab = tabList.firstOrNull { it.tabId == tabId }
        if (alreadyCreatedTab != null) return alreadyCreatedTab

        val newTab = createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
        return newTab
    }

    fun selectTab(tabId: String?) {
        if (selectedTabId == tabId) {
            return
        }
        selectedTabId = tabId
        publishState()
    }

    internal fun restoreTabs(
        homepageUrl: String,
        persistedTabs: List<PersistedBrowserTab>,
        persistedSelectedTabIndex: Int,
    ): String {
        if (persistedTabs.isEmpty()) {
            val tabId = UUID.randomUUID().toString()
            val initialTab = createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
            selectTab(initialTab.tabId)
            return initialTab.tabId
        }

        persistedTabs.forEach { persistedTab ->
            createAndAppendTab(
                tabId = persistedTab.tabId,
                initialUrl = persistedTab.url.ifBlank { homepageUrl },
                restoredSessionState = persistedTab.sessionState,
                restoredTitle = persistedTab.title,
                restoredPreviewImage = persistedTab.previewImageWebp,
                restoredThemeColor = persistedTab.themeColor,
                openerTabId = persistedTab.openerTabId,
            )
        }
        val index = persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)
        return tabs[index].tabId.also(::selectTab)
    }

    fun createAndAppendTab(
        tabId: String = UUID.randomUUID().toString(),
        initialUrl: String,
        restoredSessionState: String? = null,
        restoredTitle: String = "",
        restoredPreviewImage: ByteArray = byteArrayOf(),
        restoredThemeColor: Int? = null,
        openerTabId: String? = null,
    ): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        val session = GeckoSession()  // open() はここでは呼ばない（遅延ロード）
        val tab = appendTab(
            tabId = tabId,
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = restoredSessionState.orEmpty(),
            title = restoredTitle,
            previewBitmapArray = restoredPreviewImage,
            themeColor = restoredThemeColor,
            openerTabId = openerTabId,
        )
        // 復元情報を保持（ensureSessionOpen で使用）
        tab.pendingSessionState = restoredSessionState?.takeIf { it.isNotBlank() }
        if (selectedTabId == null) {
            selectTab(tab.tabId)
        }
        return tab
    }

    fun restoreSession(tab: BrowserTab) {
        if (tab.session.isOpen) return
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
        val session = GeckoSession()
        return appendTab(
            tabId = UUID.randomUUID().toString(),
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmapArray = null,
            openerTabId = openerTabId,
        )
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
        return appendTab(
            tabId = tabId,
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmapArray = null,
            openerTabId = openerTabId,
        )
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex < 0 || fromIndex >= tabList.size) return
        if (toIndex < 0 || toIndex >= tabList.size) return
        tabList.add(toIndex, tabList.removeAt(fromIndex))
        publishState()
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
        return selectedTabId
    }

    internal fun exportPersistedTabs(): List<PersistedBrowserTab> {
        return tabList.map { tab ->
            PersistedBrowserTab(
                url = tab.currentUrl,
                sessionState = tab.sessionState,
                title = tab.title,
                previewImageWebp = tab.previewBitmap ?: byteArrayOf(),
                tabId = tab.tabId,
                openerTabId = tab.openerTabId,
                themeColor = tab.themeColor,
            )
        }
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
    ): BrowserTab {
        val tab = BrowserTab(
            tabId = tabId,
            session = session,
            currentUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmap = previewBitmapArray ?: byteArrayOf(),
            themeColor = themeColor,
            openerTabId = openerTabId,
            onStateChanged = ::publishState,
        )
        tabList += tab
        publishState()
        return tab
    }

    private fun publishState() {
        _tabStoreState.value = TabStoreState(
            tabs = tabList.map { tab -> tab.toSummary() },
            selectedTabId = selectedTabId,
        )
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
) {
    private var currentUrlState by mutableStateOf(currentUrl)
    var currentUrl: String
        get() = currentUrlState
        set(value) {
            if (currentUrlState == value) return
            currentUrlState = value
            onStateChanged()
        }
    private var sessionStateState by mutableStateOf(sessionState)
    var sessionState: String
        get() = sessionStateState
        set(value) {
            if (sessionStateState == value) return
            sessionStateState = value
            onStateChanged()
        }
    private var titleState by mutableStateOf(title)
    var title: String
        get() = titleState
        set(value) {
            if (titleState == value) return
            titleState = value
            onStateChanged()
        }
    private var previewBitmapState: ByteArray? by mutableStateOf(previewBitmap)
    var previewBitmap: ByteArray?
        get() = previewBitmapState
        set(value) {
            if (previewBitmapState.contentEqualsNullable(value)) return
            previewBitmapState = value
            onStateChanged()
        }
    private var themeColorState: Int? by mutableStateOf(themeColor)
    var themeColor: Int?
        get() = themeColorState
        set(value) {
            if (themeColorState == value) return
            themeColorState = value
            onStateChanged()
        }
    // 未オープンタブのセッション復元情報を保持
    internal var pendingSessionState: String? by mutableStateOf(null)
}

internal data class PersistedBrowserTab(
    val url: String,
    val sessionState: String,
    val title: String,
    val previewImageWebp: ByteArray = byteArrayOf(),
    val tabId: String = UUID.randomUUID().toString(),
    val openerTabId: String? = null,
    val themeColor: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedBrowserTab) return false
        return url == other.url &&
            sessionState == other.sessionState &&
            title == other.title &&
            previewImageWebp.contentEquals(other.previewImageWebp) &&
            tabId == other.tabId &&
            openerTabId == other.openerTabId &&
            themeColor == other.themeColor
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + sessionState.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + previewImageWebp.contentHashCode()
        result = 31 * result + tabId.hashCode()
        result = 31 * result + openerTabId.hashCode()
        result = 31 * result + (themeColor ?: 0)
        return result
    }
}

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



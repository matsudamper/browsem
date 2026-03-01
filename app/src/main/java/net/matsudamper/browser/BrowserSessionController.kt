package net.matsudamper.browser

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal class BrowserSessionController(runtime: GeckoRuntime) {
    private val geckoRuntime = runtime
    private var nextTabId = 1L
    private val tabList = mutableStateListOf<BrowserTab>()
    private var selectedTabId by mutableLongStateOf(-1L)

    val tabs: List<BrowserTab>
        get() = tabList

    val selectedTab: BrowserTab?
        get() = tabList.firstOrNull { it.id == selectedTabId }

    val selectedTabIndex: Int
        get() = tabList.indexOfFirst { it.id == selectedTabId }
            .takeIf { it >= 0 }
            ?: 0

    fun ensureInitialPageLoaded(
        homepageUrl: String,
        persistedTabs: List<PersistedBrowserTab> = emptyList(),
        persistedSelectedTabIndex: Int = 0,
    ) {
        if (tabList.isNotEmpty()) {
            return
        }
        if (persistedTabs.isEmpty()) {
            val initialTab = createTab(initialUrl = homepageUrl)
            selectedTabId = initialTab.id
            return
        }

        persistedTabs.forEach { persistedTab ->
            createTab(
                initialUrl = persistedTab.url.ifBlank { homepageUrl },
                restoredSessionState = persistedTab.sessionState,
                restoredTitle = persistedTab.title,
            )
        }
        val index = persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)
        selectedTabId = tabList[index].id
    }

    fun createTab(
        initialUrl: String,
        restoredSessionState: String? = null,
        restoredTitle: String = "",
    ): BrowserTab {
        val session = GeckoSession().also { it.open(geckoRuntime) }
        val tab = BrowserTab(
            id = nextTabId++,
            session = session,
            currentUrl = initialUrl,
            sessionState = restoredSessionState.orEmpty(),
            title = restoredTitle.ifBlank { initialUrl },
        )
        tabList += tab
        val restored = restoredSessionState
            ?.takeIf { it.isNotBlank() }
            ?.let { sessionState ->
                val parsed = GeckoSession.SessionState.fromString(sessionState) ?: return@let false
                session.restoreState(parsed)
                true
            } == true
        if (!restored) {
            session.loadUri(initialUrl)
        }
        return tab
    }

    fun selectTab(tabId: Long) {
        if (tabList.any { it.id == tabId }) {
            selectedTabId = tabId
        }
    }

    fun selectPreviousTab(): Boolean {
        val currentIndex = tabList.indexOfFirst { it.id == selectedTabId }
        if (currentIndex <= 0) {
            return false
        }
        selectedTabId = tabList[currentIndex - 1].id
        return true
    }

    fun selectNextTab(): Boolean {
        val currentIndex = tabList.indexOfFirst { it.id == selectedTabId }
        if (currentIndex < 0 || currentIndex >= tabList.lastIndex) {
            return false
        }
        selectedTabId = tabList[currentIndex + 1].id
        return true
    }

    fun updateTabUrl(tabId: Long, url: String) {
        tabList.firstOrNull { it.id == tabId }?.currentUrl = url
    }

    fun updateTabSessionState(tabId: Long, sessionState: String) {
        tabList.firstOrNull { it.id == tabId }?.sessionState = sessionState
    }

    fun updateTabTitle(tabId: Long, title: String) {
        val normalized = title.ifBlank { return }
        tabList.firstOrNull { it.id == tabId }?.title = normalized
    }

    fun updateTabPreview(tabId: Long, previewBitmap: Bitmap) {
        tabList.firstOrNull { it.id == tabId }?.previewBitmap = previewBitmap
    }

    fun closeTab(tabId: Long) {
        val index = tabList.indexOfFirst { it.id == tabId }
        if (index < 0) {
            return
        }
        val removed = tabList.removeAt(index)
        removed.session.close()
        if (selectedTabId == tabId) {
            if (tabList.isEmpty()) {
                selectedTabId = -1L
            } else {
                val nextIndex = index.coerceAtMost(tabList.lastIndex)
                selectedTabId = tabList[nextIndex].id
            }
        }
    }

    fun exportPersistedTabs(): List<PersistedBrowserTab> {
        return tabList.map { tab ->
            PersistedBrowserTab(
                url = tab.currentUrl,
                sessionState = tab.sessionState,
                title = tab.title,
            )
        }
    }

    fun close() {
        tabList.forEach { tab ->
            tab.session.close()
        }
        tabList.clear()
        selectedTabId = -1L
    }
}

internal class BrowserTab(
    val id: Long,
    val session: GeckoSession,
    currentUrl: String,
    sessionState: String,
    title: String,
) {
    var currentUrl by mutableStateOf(currentUrl)
    var sessionState by mutableStateOf(sessionState)
    var title by mutableStateOf(title)
    var previewBitmap: Bitmap? by mutableStateOf(null)
}

internal data class PersistedBrowserTab(
    val url: String,
    val sessionState: String,
    val title: String,
)

@Composable
internal fun rememberBrowserSessionController(runtime: GeckoRuntime): BrowserSessionController {
    val controller = remember(runtime) { BrowserSessionController(runtime) }
    DisposableEffect(controller) {
        onDispose {
            controller.close()
        }
    }
    return controller
}

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
            )
        }
        val index = persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)
        selectedTabId = tabList[index].id
    }

    fun createTab(
        initialUrl: String,
        restoredSessionState: String? = null,
    ): BrowserTab {
        val session = GeckoSession().also { it.open(geckoRuntime) }
        val tab = BrowserTab(
            id = nextTabId++,
            session = session,
            currentUrl = initialUrl,
            sessionState = restoredSessionState.orEmpty(),
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

    fun updateTabUrl(tabId: Long, url: String) {
        tabList.firstOrNull { it.id == tabId }?.currentUrl = url
    }

    fun updateTabSessionState(tabId: Long, sessionState: String) {
        tabList.firstOrNull { it.id == tabId }?.sessionState = sessionState
    }

    fun updateTabPreview(tabId: Long, previewBitmap: Bitmap) {
        tabList.firstOrNull { it.id == tabId }?.previewBitmap = previewBitmap
    }

    fun exportPersistedTabs(): List<PersistedBrowserTab> {
        return tabList.map { tab ->
            PersistedBrowserTab(
                url = tab.currentUrl,
                sessionState = tab.sessionState,
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
) {
    var currentUrl by mutableStateOf(currentUrl)
    var sessionState by mutableStateOf(sessionState)
    var previewBitmap: Bitmap? by mutableStateOf(null)
}

internal data class PersistedBrowserTab(
    val url: String,
    val sessionState: String,
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

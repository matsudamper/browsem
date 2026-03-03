package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.io.ByteArrayOutputStream
import java.util.UUID

@Stable
internal class BrowserSessionController(runtime: GeckoRuntime) {
    private val geckoRuntime = runtime
    private val tabList = mutableStateListOf<BrowserTab>()
    private var selectedTabId: String? by mutableStateOf(null)

    val stateChanged by derivedStateOf {
        mutableIntStateOf(
            tabList.map {
                it.currentUrl
                it.sessionState
                it.title
                it.previewBitmap
            }.hashCode()
        )
    }

    val tabs: List<BrowserTab>
        get() = tabList

    val selectedTabIndex: Int
        get() = tabList.indexOfFirst { it.tabId == selectedTabId }
            .takeIf { it >= 0 }
            ?: 0

    val currentTab: BrowserTab?
        get() = tabList.firstOrNull { it.tabId == selectedTabId }

    fun getOrCreateTab(tabId: String, homepageUrl: String): BrowserTab {
        Log.d("LOG", "getOrCreateTab: tabList=${tabList.size}")
        val alreadyCreatedTab = tabList.firstOrNull { it.tabId == tabId }
        if (alreadyCreatedTab != null) return alreadyCreatedTab

        val newTab = createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
        return newTab
    }

    fun restoreTabs(
        homepageUrl: String,
        persistedTabs: List<PersistedBrowserTab>,
        persistedSelectedTabIndex: Int,
    ) {
        if (tabList.isNotEmpty()) return
        if (persistedTabs.isEmpty()) {
            val tabId = UUID.randomUUID().toString()
            val initialTab = createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
            selectedTabId = initialTab.tabId
            return
        }

        persistedTabs.forEach { persistedTab ->
            val tabId = UUID.randomUUID().toString()
            createAndAppendTab(
                tabId = tabId,
                initialUrl = persistedTab.url.ifBlank { homepageUrl },
                restoredSessionState = persistedTab.sessionState,
                restoredTitle = persistedTab.title,
                restoredPreviewImage = persistedTab.previewImageWebp,
            )
        }
        val index = persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)
        selectedTabId = tabList[index].tabId
    }

    fun createAndAppendTab(
        tabId: String = UUID.randomUUID().toString(),
        initialUrl: String,
        restoredSessionState: String? = null,
        restoredTitle: String = "",
        restoredPreviewImage: ByteArray = byteArrayOf(),
    ): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        val session = GeckoSession().also { it.open(geckoRuntime) }
        val tab = appendTab(
            tabId = tabId,
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = restoredSessionState.orEmpty(),
            title = restoredTitle,
            previewBitmapArray = restoredPreviewImage,
        )
        val restored = restoredSessionState
            ?.takeIf { it.isNotBlank() }
            ?.let { sessionState ->
                val parsed = GeckoSession.SessionState.fromString(sessionState) ?: return@let false
                session.restoreState(parsed)
                true
            } == true
        if (!restored) {
            session.loadUri(normalizedInitialUrl)
        }
        return tab
    }

    fun createTabForNewSession(initialUrl: String): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        return appendTab(
            tabId = UUID.randomUUID().toString(),
            session = GeckoSession(),
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmapArray = null,
        )
    }

    fun selectTab(tabId: String): BrowserTab {
        val targetTab = tabList.firstOrNull { it.tabId == tabId }
        return if (targetTab != null) {
            selectedTabId = tabId
            targetTab
        } else {
            // TODO homepage
            createAndAppendTab(initialUrl = "")
        }
    }

    fun closeTab(tabId: String) {
        val index = tabList.indexOfFirst { it.tabId == tabId }
        if (index < 0) {
            return
        }
        val removed = tabList.removeAt(index)
        if (removed.session.isOpen) {
            removed.session.close()
        }
        if (selectedTabId == tabId) {
            if (tabList.isEmpty()) {
                selectedTabId = null
            } else {
                val nextIndex = index.coerceAtMost(tabList.lastIndex)
                selectedTabId = tabList[nextIndex].tabId
            }
        }
    }

    fun exportPersistedTabs(): List<PersistedBrowserTab> {
        return tabList.map { tab ->
            PersistedBrowserTab(
                url = tab.currentUrl,
                sessionState = tab.sessionState,
                title = tab.title,
                previewImageWebp = tab.previewBitmap ?: byteArrayOf(),
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
    }

    private fun appendTab(
        tabId: String,
        session: GeckoSession,
        initialUrl: String,
        sessionState: String,
        title: String,
        previewBitmapArray: ByteArray?,
    ): BrowserTab {
        val tab = BrowserTab(
            tabId = tabId,
            session = session,
            currentUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmap = previewBitmapArray ?: byteArrayOf(),
        )
        tabList += tab
        return tab
    }
}

@Stable
class BrowserTab(
    val tabId: String,
    val session: GeckoSession,
    currentUrl: String,
    sessionState: String,
    title: String,
    previewBitmap: ByteArray?,
) {
    var currentUrl by mutableStateOf(currentUrl)
    var sessionState by mutableStateOf(sessionState)
    var title by mutableStateOf(title)
    var previewBitmap: ByteArray? by mutableStateOf(previewBitmap)
}

internal data class PersistedBrowserTab(
    val url: String,
    val sessionState: String,
    val title: String,
    val previewImageWebp: ByteArray = byteArrayOf(),
)

private fun Bitmap?.toWebpByteArray(): ByteArray {
    val bitmap = this ?: return byteArrayOf()
    val outputStream = ByteArrayOutputStream()
    val format = Bitmap.CompressFormat.WEBP_LOSSY
    bitmap.compress(format, 80, outputStream)
    return outputStream.toByteArray()
}

private fun ByteArray.toBitmapOrNull(): Bitmap? {
    if (isEmpty()) return null
    return BitmapFactory.decodeByteArray(this, 0, size)
}



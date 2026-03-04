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
    ) : String {
        if (persistedTabs.isEmpty()) {
            val tabId = UUID.randomUUID().toString()
            val initialTab = createAndAppendTab(tabId = tabId, initialUrl = homepageUrl)
            return initialTab.tabId
        }

        persistedTabs.forEach { persistedTab ->
            createAndAppendTab(
                tabId = persistedTab.tabId,
                initialUrl = persistedTab.url.ifBlank { homepageUrl },
                restoredSessionState = persistedTab.sessionState,
                restoredTitle = persistedTab.title,
                restoredPreviewImage = persistedTab.previewImageWebp,
                openerTabId = persistedTab.openerTabId,
            )
        }
        val index = persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)
        return tabs[index].tabId
    }

    fun createAndAppendTab(
        tabId: String = UUID.randomUUID().toString(),
        initialUrl: String,
        restoredSessionState: String? = null,
        restoredTitle: String = "",
        restoredPreviewImage: ByteArray = byteArrayOf(),
        openerTabId: String? = null,
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
            openerTabId = openerTabId,
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

    fun closeTab(tabId: String) {
        val index = tabList.indexOfFirst { it.tabId == tabId }
        if (index < 0) {
            return
        }
        val removed = tabList.removeAt(index)
        if (removed.session.isOpen) {
            removed.session.close()
        }
    }

    fun exportPersistedTabs(): List<PersistedBrowserTab> {
        return tabList.map { tab ->
            PersistedBrowserTab(
                url = tab.currentUrl,
                sessionState = tab.sessionState,
                title = tab.title,
                previewImageWebp = tab.previewBitmap ?: byteArrayOf(),
                tabId = tab.tabId,
                openerTabId = tab.openerTabId,
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
    }

    private fun appendTab(
        tabId: String,
        session: GeckoSession,
        initialUrl: String,
        sessionState: String,
        title: String,
        previewBitmapArray: ByteArray?,
        openerTabId: String? = null,
    ): BrowserTab {
        val tab = BrowserTab(
            tabId = tabId,
            session = session,
            currentUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmap = previewBitmapArray ?: byteArrayOf(),
            openerTabId = openerTabId,
        )
        tabList += tab
        return tab
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
    val tabId: String = UUID.randomUUID().toString(),
    val openerTabId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedBrowserTab) return false
        return url == other.url &&
            sessionState == other.sessionState &&
            title == other.title &&
            previewImageWebp.contentEquals(other.previewImageWebp) &&
            tabId == other.tabId &&
            openerTabId == other.openerTabId
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + sessionState.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + previewImageWebp.contentHashCode()
        result = 31 * result + tabId.hashCode()
        result = 31 * result + openerTabId.hashCode()
        return result
    }
}

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



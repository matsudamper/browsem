package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.io.ByteArrayOutputStream
import java.util.UUID

@Stable
class BrowserSessionController(runtime: GeckoRuntime) {
    private val geckoRuntime = runtime
    private val tabList = mutableStateListOf<BrowserTab>()

    /**
     * タブコンテンツの変更バージョン。
     * Compose snapshotシステムにより、tabList内の各プロパティ変更を自動追跡する。
     */
    val contentVersion by derivedStateOf {
        tabList.fold(0) { acc, tab ->
            var h = acc
            h = 31 * h + tab.currentUrl.hashCode()
            h = 31 * h + tab.sessionState.hashCode()
            h = 31 * h + tab.title.hashCode()
            h = 31 * h + (tab.previewBitmap?.contentHashCode() ?: 0)
            h = 31 * h + (tab.themeColor ?: 0)
            h
        }
    }

    /**
     * タブ構造変更（追加・削除・並べ替え）や選択タブ変更時に手動インクリメントするカウンター。
     * [contentVersion] が検出しない変更（選択タブの変更等）を補完する。
     */
    var structuralVersion by mutableLongStateOf(0L)
        private set

    fun notifyStructuralChange() {
        structuralVersion++
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

    internal fun restoreTabs(
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
                restoredThemeColor = persistedTab.themeColor,
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

    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex < 0 || fromIndex >= tabList.size) return
        if (toIndex < 0 || toIndex >= tabList.size) return
        tabList.add(toIndex, tabList.removeAt(fromIndex))
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
    themeColor: Int? = null,
) {
    var currentUrl by mutableStateOf(currentUrl)
    var sessionState by mutableStateOf(sessionState)
    var title by mutableStateOf(title)
    var previewBitmap: ByteArray? by mutableStateOf(previewBitmap)
    var themeColor: Int? by mutableStateOf(themeColor)
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



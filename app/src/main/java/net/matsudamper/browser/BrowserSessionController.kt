package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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

    val tabs: List<BrowserTab>
        get() = tabList

    /**
     * タブが未作成の場合のみ初期タブを生成する。
     * @return 最初に表示すべきタブの ID
     */
    fun ensureInitialPageLoaded(
        homepageUrl: String,
        persistedTabs: List<PersistedBrowserTab> = emptyList(),
        persistedSelectedTabIndex: Int = 0,
    ): Long {
        if (tabList.isNotEmpty()) {
            return tabList[persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)].id
        }
        if (persistedTabs.isEmpty()) {
            val initialTab = createTab(initialUrl = homepageUrl)
            return initialTab.id
        }

        persistedTabs.forEach { persistedTab ->
            createTab(
                initialUrl = persistedTab.url.ifBlank { homepageUrl },
                restoredSessionState = persistedTab.sessionState,
                restoredTitle = persistedTab.title,
                restoredPreviewImage = persistedTab.previewImageWebp,
            )
        }
        val index = persistedSelectedTabIndex.coerceIn(0, tabList.lastIndex)
        return tabList[index].id
    }

    fun createTab(
        initialUrl: String,
        restoredSessionState: String? = null,
        restoredTitle: String = "",
        restoredPreviewImage: ByteArray = byteArrayOf(),
    ): BrowserTab {
        val normalizedInitialUrl = initialUrl.ifBlank { "about:blank" }
        val session = GeckoSession().also { it.open(geckoRuntime) }
        val tab = appendTab(
            session = session,
            initialUrl = normalizedInitialUrl,
            sessionState = restoredSessionState.orEmpty(),
            title = restoredTitle,
            previewBitmap = restoredPreviewImage.toBitmapOrNull(),
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
            session = GeckoSession(),
            initialUrl = normalizedInitialUrl,
            sessionState = "",
            title = normalizedInitialUrl,
            previewBitmap = null,
        )
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

    /**
     * タブを閉じる。
     * @return 次に選択すべきタブID、タブが空になった場合は null
     */
    fun closeTab(tabId: Long): Long? {
        val index = tabList.indexOfFirst { it.id == tabId }
        if (index < 0) {
            return tabList.firstOrNull()?.id
        }
        val removed = tabList.removeAt(index)
        if (removed.session.isOpen) {
            removed.session.close()
        }
        if (tabList.isEmpty()) return null
        val nextIndex = index.coerceAtMost(tabList.lastIndex)
        return tabList[nextIndex].id
    }

    fun exportPersistedTabs(currentTabId: Long): List<PersistedBrowserTab> {
        return tabList.map { tab ->
            PersistedBrowserTab(
                url = tab.currentUrl,
                sessionState = tab.sessionState,
                title = tab.title,
                previewImageWebp = tab.previewBitmap.toWebpByteArray(),
            )
        }
    }

    fun selectedTabIndex(currentTabId: Long): Int {
        return tabList.indexOfFirst { it.id == currentTabId }.takeIf { it >= 0 } ?: 0
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
        session: GeckoSession,
        initialUrl: String,
        sessionState: String,
        title: String,
        previewBitmap: Bitmap?,
    ): BrowserTab {
        val tab = BrowserTab(
            id = nextTabId++,
            session = session,
            currentUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmap = previewBitmap,
        )
        tabList += tab
        return tab
    }
}

internal class BrowserTab(
    val id: Long,
    val session: GeckoSession,
    currentUrl: String,
    sessionState: String,
    title: String,
    previewBitmap: Bitmap?,
) {
    var currentUrl by mutableStateOf(currentUrl)
    var sessionState by mutableStateOf(sessionState)
    var title by mutableStateOf(title)
    var previewBitmap: Bitmap? by mutableStateOf(previewBitmap)
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
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    bitmap.compress(format, 80, outputStream)
    return outputStream.toByteArray()
}

private fun ByteArray.toBitmapOrNull(): Bitmap? {
    if (isEmpty()) return null
    return BitmapFactory.decodeByteArray(this, 0, size)
}

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

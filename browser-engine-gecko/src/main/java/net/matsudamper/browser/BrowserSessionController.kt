package net.matsudamper.browser

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

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
class BrowserSessionLifecycleController(
    private val geckoRuntime: GeckoRuntime,
) {
    /**
     * タブを前面表示して利用可能にする直前に呼ぶ。
     *
     * 主な利用タイミング:
     * - タブ切り替えで対象タブを表示するとき
     * - 画面再表示で現在タブを再アタッチするとき
     */
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

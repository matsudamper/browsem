package net.matsudamper.browser

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.core.TabStoreState
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

@Stable
class BrowserSessionController internal constructor(
    val browserTabController: BrowserTabController,
) : TabStore {
    override val tabStoreState: StateFlow<TabStoreState>
        get() = browserTabController.tabStoreState

    val selectedTabId: String?
        get() = browserTabController.selectedTabId

    val tabs: List<BrowserTab>
        get() = browserTabController.tabs


    override fun moveTab(fromIndex: Int, toIndex: Int) {
        browserTabController.moveTab(fromIndex, toIndex)
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

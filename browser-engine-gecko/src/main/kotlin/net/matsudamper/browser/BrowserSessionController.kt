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
    // 拡張機能 (AdGuard 等) に「現在アクティブなタブ」を通知するための追跡用。
    // WebExtensionController.setTabActive(session, true) は同時に一つの session のみ
    // active として扱うのが意図された使い方なので、直前の active session を覚えておき
    // 切り替え時に false→true の順で更新する。
    private var activeExtensionSession: GeckoSession? = null

    /**
     * タブを前面表示して利用可能にする直前に呼ぶ。
     *
     * 主な利用タイミング:
     * - タブ切り替えで対象タブを表示するとき
     * - 画面再表示で現在タブを再アタッチするとき
     */
    fun restoreSession(tab: BrowserTab) {
        if (tab.session.isOpen) {
            // onNewSession 経由のタブ (pendingInitialUrl != null) であっても、ここで
            // loadUri してはいけない。GeckoView が opener (window.open 元ページ) の
            // コンテキスト付きで自動読み込みするため、アプリ側から loadUri すると
            // その読み込みを上書きして referrer / opener 連携が失われ、
            // Google Pay などのポップアップ決済が「販売者に戻れない」エラーになる。
            tab.session.setActive(true)
            markActiveForExtensions(tab.session)
            return
        }
        // onNewSession 経由のタブは GeckoView 自身が open して初回読み込みを行うため、
        // ここで open / loadUri せず待つ
        if (tab.pendingInitialUrl != null) {
            return
        }
        tab.session.open(geckoRuntime)
        val state = tab.pendingSessionState
        if (state != null) {
            tab.pendingSessionState = null
            val parsed = GeckoSession.SessionState.fromString(state)
            if (parsed != null) {
                // SessionState から履歴を抽出してキャッシュに反映する
                // （restoreState は onHistoryStateChange を発火しないため）
                tab.initHistoryFromSessionState(parsed)
                tab.session.restoreState(parsed)
                tab.session.setActive(true)
                markActiveForExtensions(tab.session)
                return
            }
        }
        val referrerUrl = tab.pendingReferrerUrl
        tab.pendingReferrerUrl = null
        if (referrerUrl != null) {
            // コンテキストメニューの「新しいタブで開く」由来のタブは元ページを referrer に
            // 付けて読み込む。ホットリンク保護のあるサーバーで 403 にならないようにするため
            tab.session.load(
                GeckoSession.Loader()
                    .uri(tab.currentUrl.ifBlank { "about:blank" })
                    .referrer(referrerUrl),
            )
        } else {
            tab.session.loadUri(tab.currentUrl.ifBlank { "about:blank" })
        }
        tab.session.setActive(true)
        markActiveForExtensions(tab.session)
    }

    /**
     * バックグラウンド遷移時に呼び、セッション側の処理を一時停止させる。
     * Surface バインドは維持するため復帰時のちらつきが少ない。
     */
    fun pauseSession(tab: BrowserTab) {
        if (tab.session.isOpen) {
            tab.session.setActive(false)
        }
    }

    /**
     * フォアグラウンド復帰時に呼び、セッション側の処理を再開させる。
     */
    fun resumeSession(tab: BrowserTab) {
        if (tab.session.isOpen) {
            tab.session.setActive(true)
            markActiveForExtensions(tab.session)
        }
    }

    /**
     * 拡張機能側に「これがアクティブタブ」と通知する。直前の active session があれば
     * 先に false で解除してから新しい session を true で設定する。webRequest 等が
     * tabId を参照して動作する拡張 (AdGuard など) は、active タブが分からないと
     * blocking をスキップすることがあるため必要。
     */
    private fun markActiveForExtensions(session: GeckoSession) {
        if (!session.isOpen) return
        if (activeExtensionSession === session) return
        val previous = activeExtensionSession
        if (previous != null && previous !== session && previous.isOpen) {
            geckoRuntime.webExtensionController.setTabActive(previous, false)
        }
        geckoRuntime.webExtensionController.setTabActive(session, true)
        activeExtensionSession = session
    }
}

package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import net.matsudamper.browser.data.TabRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * カスタムタブのタブ管理を保持する ViewModel。
 *
 * 画面回転などの構成変更で Activity が作り直されても GeckoSession を開いたまま維持する。
 * `window.open` から引き渡されたセッションは URL の再読み込みで作り直せないため、
 * Activity 側で保持すると構成変更で内容が失われる。
 *
 * カスタムタブは一時的なセッションのため、タブ状態は DB に永続化しない。
 */
internal class CustomTabBrowserViewModel(
    tabRepository: TabRepository,
    runtime: GeckoRuntime,
    handoffToken: String?,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        isSinglePage = true,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    // 取り出したセッションはタブへ載せるまでどこからも参照されない。設定の読み込み待ちなどで
    // 載せる前に画面が終わると、開いたままのセッションと opener の保持が残るため、
    // 載せるまでの間はこの ViewModel が持ち主になる。
    private val pendingHandoff = handoffToken?.let { WindowOpenHandoffStore.consume(it) }
    private var unattachedHandoffSession: GeckoSession? = pendingHandoff?.session

    val handoffInitialUrl: String? = pendingHandoff?.initialUrl
    val handoffTabId: String? = pendingHandoff?.tabId

    /** タブへ載せる前に window.close が呼ばれていたか。 */
    val isHandoffCloseRequested: Boolean
        get() = pendingHandoff?.holdingDelegate?.isCloseRequested == true
    val handoffSession: GeckoSession?
        get() = unattachedHandoffSession

    fun onHandoffSessionAttached() {
        unattachedHandoffSession = null
    }

    init {
        browserTabController.onTabListChanged = {
            browserSessionLifecycleController.retainOpenersOfLivePopups(
                tabs = browserTabController.tabs,
                selectedTabId = browserTabController.selectedTabId,
            )
        }
    }

    override fun onCleared() {
        unattachedHandoffSession?.let { session ->
            HandedOffPopupRegistry.unregister(session)
            if (session.isOpen) {
                session.close()
            }
        }
        unattachedHandoffSession = null
        browserTabController.close()
    }
}

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
    private var pendingHandoff = handoffToken?.let { WindowOpenHandoffStore.consume(it) }

    /** 引き渡しを受け取れたか。載せ終えた後も、構成変更で作り直された画面から見て変わらない。 */
    val hasHandoff: Boolean = pendingHandoff != null
    val handoffTabId: String? = pendingHandoff?.tabId

    val handoffSession: GeckoSession?
        get() = pendingHandoff?.session

    /**
     * タブへ載せる URL。載せるまでに遷移していれば要求時の URL ではなく遷移先を使うため、
     * 載せる直前に読む必要がある。
     */
    fun currentHandoffInitialUrl(): String? {
        return pendingHandoff?.let { it.holdingDelegate.latestLocation ?: it.initialUrl }
    }

    /**
     * タブへ載せ終えたことを記録し、載せる前に window.close が呼ばれていたかを返す。
     *
     * 暫定 delegate は起動元の Activity を捕まえているため、載せたら参照を手放す。
     */
    fun onHandoffSessionAttached(): Boolean {
        val closeRequested = pendingHandoff?.holdingDelegate?.isCloseRequested == true
        pendingHandoff = null
        return closeRequested
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
        pendingHandoff?.session?.let { session ->
            HandedOffPopupRegistry.unregister(session)
            if (session.isOpen) {
                session.close()
            }
        }
        pendingHandoff = null
        browserTabController.close()
    }
}

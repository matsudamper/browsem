package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import net.matsudamper.browser.data.TabRepository
import org.mozilla.geckoview.GeckoRuntime

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
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        isSinglePage = true,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    init {
        browserTabController.onTabListChanged = {
            browserSessionLifecycleController.retainOpenersOfLivePopups(
                tabs = browserTabController.tabs,
                selectedTabId = browserTabController.selectedTabId,
            )
        }
    }

    override fun onCleared() {
        browserTabController.close()
    }
}

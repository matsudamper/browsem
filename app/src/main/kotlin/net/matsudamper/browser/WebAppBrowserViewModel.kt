package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import net.matsudamper.browser.data.TabRepository
import org.mozilla.geckoview.GeckoRuntime

internal class WebAppBrowserViewModel(
    tabRepository: TabRepository,
    runtime: GeckoRuntime,
    applicationScope: CoroutineScope,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        isSinglePage = true,
        persistenceScope = applicationScope,
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

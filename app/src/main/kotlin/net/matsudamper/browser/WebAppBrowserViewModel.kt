package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import org.mozilla.geckoview.GeckoRuntime

internal class WebAppBrowserViewModel(
    tabRepository: TabRepository,
    runtime: GeckoRuntime,
    private val mediaWebExtension: MediaWebExtension,
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
        browserTabController.onTabSessionDisposed = { session ->
            mediaWebExtension.releaseSession(session)
        }
    }

    override fun onCleared() {
        browserTabController.close()
    }
}

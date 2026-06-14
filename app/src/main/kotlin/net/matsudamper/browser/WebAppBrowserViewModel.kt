package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import net.matsudamper.browser.data.TabRepository
import org.mozilla.geckoview.GeckoRuntime

internal class WebAppBrowserViewModel(
    tabRepository: TabRepository,
    runtime: GeckoRuntime,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        isSinglePage = true,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    override fun onCleared() {
        browserTabController.close()
    }
}

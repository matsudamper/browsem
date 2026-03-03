package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import org.mozilla.geckoview.GeckoRuntime

internal class BrowserViewModel(runtime: GeckoRuntime) : ViewModel() {
    val browserSessionController = BrowserSessionController(runtime)

    override fun onCleared() {
        super.onCleared()
        browserSessionController.close()
    }
}

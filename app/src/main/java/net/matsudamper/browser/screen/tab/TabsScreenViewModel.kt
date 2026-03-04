package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import net.matsudamper.browser.BrowserSessionController


internal class TabsScreenViewModel(
    private val browserSessionController: BrowserSessionController,
) : ViewModel() {
    val tabs = browserSessionController.tabs.map {
        TabsScreenTabData(
            id = it.tabId,
            title = it.title,
            previewBitmapArray = it.previewBitmap,
        )
    }
}

internal data class TabsScreenTabData(
    val id: String,
    val title: String,
    val previewBitmapArray: ByteArray?,
)

package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import net.matsudamper.browser.BrowserSessionController


internal class TabsScreenViewModel(
    private val browserSessionController: BrowserSessionController,
) : ViewModel() {
    val tabs: List<TabsScreenTabData>
        get() = browserSessionController.tabs.map {
            TabsScreenTabData(
                id = it.tabId,
                title = it.title,
                previewBitmapArray = it.previewBitmap,
            )
        }

    fun reorderTabs(fromIndex: Int, toIndex: Int) {
        browserSessionController.moveTab(fromIndex, toIndex)
    }
}

internal data class TabsScreenTabData(
    val id: String,
    val title: String,
    val previewBitmapArray: ByteArray?,
)

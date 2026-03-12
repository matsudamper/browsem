package net.matsudamper.browser.screen.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.matsudamper.browser.BrowserSessionController


class TabsScreenViewModel(
    private val browserSessionController: BrowserSessionController,
) : ViewModel() {
    val tabs: StateFlow<List<TabsScreenTabData>> = browserSessionController.tabStoreState
        .map { state ->
            state.tabs.map { tab ->
                TabsScreenTabData(
                    id = tab.id,
                    title = tab.title,
                    previewBitmapArray = tab.previewBitmapArray,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    fun reorderTabs(fromIndex: Int, toIndex: Int) {
        browserSessionController.moveTab(fromIndex, toIndex)
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class TabsScreenTabData(
    val id: String,
    val title: String,
    val previewBitmapArray: ByteArray?,
)

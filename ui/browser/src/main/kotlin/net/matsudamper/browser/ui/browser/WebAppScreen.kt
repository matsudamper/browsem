package net.matsudamper.browser.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.matsudamper.browser.BrowserTab
import net.matsudamper.browser.BrowserTabController

@Composable
fun WebAppScreen(
    initialUrl: String,
    browserTabController: BrowserTabController,
    uiState: BrowserScreenUiState,
    modifier: Modifier = Modifier,
    browserTabContent: @Composable (
        modifier: Modifier,
        browserTab: BrowserTab,
        uiState: BrowserScreenUiState,
    ) -> Unit,
) {
    // Activity再生成（フォルダブル開閉等）時はViewModelのcontrollerに既存タブが残っているため再利用する。
    // タブの破棄はViewModelの onCleared() で行う。
    val browserTab by produceState<BrowserTab?>(
        initialValue = null,
        key1 = browserTabController,
        key2 = initialUrl,
    ) {
        value = browserTabController.tabs.firstOrNull()
            ?: browserTabController.createAndAppendTab(initialUrl = initialUrl)
    }

    val activeTab = browserTab
    if (activeTab == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    browserTabContent(
        modifier.fillMaxSize(),
        activeTab,
        uiState,
    )
}

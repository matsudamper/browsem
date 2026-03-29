package net.matsudamper.browser.ui.browser

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.runBlocking
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
    val browserTab = remember(browserTabController, initialUrl) {
        // TODO runBlocking使わない
        runBlocking {
            browserTabController.createAndAppendTab(initialUrl = initialUrl)
        }
    }
    DisposableEffect(browserTabController, browserTab.tabId) {
        onDispose {
            browserTabController.closeTab(browserTab.tabId)
        }
    }

    browserTabContent(
        modifier.fillMaxSize(),
        browserTab,
        uiState,
    )
}

package net.matsudamper.browser.screen.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import net.matsudamper.browser.BrowserSessionController
import net.matsudamper.browser.GeckoBrowserTab
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.NavController
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun BrowserScreen(
    key: AppDestination.Browser,
    homepageUrl: String,
    searchTemplate: String,
    backStack: MutableList<NavKey>,
    browserSessionController: BrowserSessionController,
    viewModel: BrowserScreenViewModel,
    navController: NavController,
    onInstallExtensionRequest: (String) -> Unit,
    handleNotificationPermission: (uri: String) -> GeckoResult<Int>,
) {
    val currentSettings by viewModel.settingsUiState.collectAsState()
    val settingsUiState = currentSettings ?: return

    val selectedTab = remember(key.tabId) {
        browserSessionController.getOrCreateTab(
            tabId = key.tabId,
            homepageUrl = homepageUrl,
        )
    }
    val tabs = browserSessionController.tabs
    GeckoBrowserTab(
        browserTab = selectedTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
        translationProvider = settingsUiState.translationProvider,
        tabCount = tabs.size,
        onInstallExtensionRequest = onInstallExtensionRequest,
        onDesktopNotificationPermissionRequest = handleNotificationPermission,
        onOpenSettings = { backStack.add(AppDestination.Settings) },
        onOpenTabs = { backStack.add(AppDestination.Tabs) },
        onOpenNewSessionRequest = { uri ->
            val newTab = browserSessionController.createTabForNewSession(
                initialUrl = uri,
                openerTabId = key.tabId,
            )
            navController.selectTab(newTab.tabId)
            newTab.session
        },
        onCloseTab = {
            val openerTabId = selectedTab.openerTabId
            browserSessionController.closeTab(key.tabId)
            viewModel.bumpTabPersistence()
            val targetTabId = openerTabId?.takeIf { id ->
                browserSessionController.tabs.any { it.tabId == id }
            } ?: browserSessionController.tabs.lastOrNull()?.tabId
            if (targetTabId != null) {
                navController.selectTab(targetTabId)
            }
        },
    )
}

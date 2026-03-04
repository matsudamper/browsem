package net.matsudamper.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.screen.tab.TabsScreen
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<String>,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
) {
    val currentSettings by viewModel.settings.collectAsState()
    val settings = currentSettings ?: return
    val currentTabData by viewModel.tabData.collectAsState()
    val tabData = currentTabData ?: return

    val homepageUrl = settings.resolvedHomepageUrl()
    val searchTemplate = settings.resolvedSearchTemplate()
    val browserSessionController = viewModel.browserSessionController

    LaunchedEffect(settings.enableThirdPartyCa) {
        viewModel.applyRuntimeSettings(settings)
    }

    val backStack = rememberNavBackStack(AppDestination.Setup)
    val navController = remember(backStack) { NavController(backStack = backStack) }

    LaunchedEffect(newTabUrlFlow) {
        newTabUrlFlow.collect { url ->
            val newTab = browserSessionController.createAndAppendTab(initialUrl = url)
            browserSessionController.selectTab(newTab.tabId)
            navController.selectTab(newTab.tabId)
        }
    }

    // Tab state persistence
    LaunchedEffect(browserSessionController) {
        snapshotFlow { browserSessionController.stateChanged }
            .collectLatest { viewModel.persistTabStates() }
    }

    val handleNotificationPermission: (uri: String) -> GeckoResult<Int> = { uri ->
        viewModel.handleNotificationPermission(
            uri = uri,
            currentSettings = settings,
            onDesktopNotificationPermissionRequest = onDesktopNotificationPermissionRequest,
        )
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
    }

    BrowserTheme(themeMode = settings.themeMode) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = {
                val default = defaultTransitionSpec<NavKey>()(this)
                val initial = initialState.entries.lastOrNull() ?: return@NavDisplay default
                val target = targetState.entries.lastOrNull() ?: return@NavDisplay default

                if (target.contentKey is AppDestination.Tabs && initial.contentKey is AppDestination.Browser) {
                    return@NavDisplay ContentTransform(
                        initialContentExit = ExitTransition.None,
                        targetContentEnter = slideIn {
                            IntOffset(x = 0, y = -it.height / 2)
                        },
                    )
                }

                if (initial.contentKey is AppDestination.Browser) {
                    return@NavDisplay ContentTransform(
                        initialContentExit = ExitTransition.None,
                        targetContentEnter = EnterTransition.None,
                    )
                }

                default
            },
            popTransitionSpec = { popTransition { height -> -height } },
            predictivePopTransitionSpec = { popTransition { height -> -height / 2 } },
            entryProvider = { key: NavKey ->
                when (key) {
                    is AppDestination.Setup -> navEntry(key) {
                        DisposableEffect(Unit) {
                            viewModel.restoreTabs(settings, tabData)
                            val tab = browserSessionController.tabs
                                .getOrNull(browserSessionController.selectedTabIndex)!!
                            navController.selectTab(tab.tabId)
                            onDispose { }
                        }
                    }

                    is AppDestination.Browser -> navEntry(key) {
                        Browser(
                            key = key,
                            homepageUrl = homepageUrl,
                            searchTemplate = searchTemplate,
                            backStack = backStack,
                            browserSessionController = browserSessionController,
                            viewModel = viewModel,
                            navController = navController,
                            onInstallExtensionRequest = onInstallExtensionRequest,
                            handleNotificationPermission = handleNotificationPermission,
                        )
                    }

                    AppDestination.Settings -> navEntry(key) {
                        val latestSettings by viewModel.settingsRepository.settings
                            .collectAsState(initial = settings)
                        SettingsScreen(
                            settings = latestSettings,
                            onSettingsChange = viewModel::updateSettings,
                            onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                            onOpenNotificationPermissions = {
                                backStack.add(AppDestination.NotificationPermissions)
                            },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Extensions -> navEntry(key) {
                        ExtensionsScreen(
                            runtime = viewModel.runtime,
                            onBack = { backStack.removeLastOrNull() },
                            onOpenExtensionSettings = { optionsPageUrl ->
                                val tab = browserSessionController.createAndAppendTab(
                                    initialUrl = optionsPageUrl,
                                )
                                navController.selectTab(tab.tabId)
                            },
                        )
                    }

                    AppDestination.NotificationPermissions -> navEntry(key) {
                        NotificationPermissionsScreen(
                            allowedOrigins = settings.notificationAllowedOriginsList,
                            onRevokeOrigin = viewModel::removeNotificationAllowedOrigin,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Tabs -> navEntry(key) {
                        DisposableEffect(Unit) {
                            onDispose { navController.disposeTabs() }
                        }
                        TabsScreen(
                            browserSessionController = browserSessionController,
                            selectedTabId = navController.getSelectedTab(),
                            onSelectTab = { tabId ->
                                val tab = browserSessionController.selectTab(tabId)
                                viewModel.bumpTabPersistence()
                                navController.selectTab(tab.tabId)
                            },
                            onCloseTab = { tabId ->
                                browserSessionController.closeTab(tabId)
                                if (browserSessionController.tabs.isEmpty()) {
                                    val newTab = browserSessionController.createAndAppendTab(
                                        initialUrl = homepageUrl,
                                    )
                                    browserSessionController.selectTab(newTab.tabId)
                                }
                                viewModel.bumpTabPersistence()
                            },
                            onOpenNewTab = {
                                val newTab = browserSessionController.createAndAppendTab(
                                    initialUrl = homepageUrl,
                                )
                                browserSessionController.selectTab(newTab.tabId)
                                viewModel.bumpTabPersistence()
                                backStack.removeLastOrNull()
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }

                    else -> error("Unknown destination: $key")
                }
            },
        )
    }
}

private fun navEntry(
    key: NavKey,
    content: @Composable (NavKey) -> Unit,
): NavEntry<NavKey> {
    return NavEntry(
        key = key,
        contentKey = key,
        content = content,
    )
}

@Composable
private fun Browser(
    key: AppDestination.Browser,
    homepageUrl: String,
    searchTemplate: String,
    backStack: MutableList<NavKey>,
    browserSessionController: BrowserSessionController,
    viewModel: BrowserViewModel,
    navController: NavController,
    onInstallExtensionRequest: (String) -> Unit,
    handleNotificationPermission: (uri: String) -> GeckoResult<Int>,
) {
    val currentSettings by viewModel.settings.collectAsState()
    val settings = currentSettings ?: return

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
        translationProvider = settings.translationProvider,
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
            browserSessionController.selectTab(newTab.tabId)
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
                browserSessionController.selectTab(targetTabId)
                navController.selectTab(targetTabId)
            }
        },
    )
}

private fun <T : NavKey> AnimatedContentTransitionScope<Scene<T>>.popTransition(
    heightProvider: (Int) -> Int,
): ContentTransform {
    val default = defaultPopTransitionSpec<T>()(this)
    val initial = initialState.entries.lastOrNull() ?: return default
    val target = targetState.entries.lastOrNull() ?: return default

    if (initial.contentKey is AppDestination.Tabs && target.contentKey is AppDestination.Browser) {
        return ContentTransform(
            initialContentExit = slideOut {
                IntOffset(x = 0, y = heightProvider(it.height))
            },
            targetContentEnter = EnterTransition.None,
        )
    }

    if (target.contentKey is AppDestination.Browser) {
        return ContentTransform(
            initialContentExit = ExitTransition.None,
            targetContentEnter = EnterTransition.None,
        )
    }

    return default
}

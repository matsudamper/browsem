package net.matsudamper.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.screen.browser.BrowserScreen
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreen
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreen
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.notificationpermissions.NotificationPermissionsScreen
import net.matsudamper.browser.screen.notificationpermissions.NotificationPermissionsScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreen
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreen
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<String>,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
) {
    val currentSettings by viewModel.settingsUiState.collectAsState()
    val settingsUiState = currentSettings ?: return

    val homepageUrl = settingsUiState.homepageUrl
    val searchTemplate = settingsUiState.searchTemplate
    val browserSessionController = viewModel.browserSessionController
    val themeColorExtension = viewModel.themeColorExtension

    LaunchedEffect(settingsUiState.enableThirdPartyCa) {
        viewModel.applyRuntimeSettings()
    }

    val backStack = rememberNavBackStack(AppDestination.Setup)
    val navController = remember(backStack) { NavController(backStack = backStack) }
    // タブ復元完了を待機するためのシグナル
    val setupComplete = remember { CompletableDeferred<Unit>() }

    // ナビゲーションとViewModelの両方にタブ選択を通知するヘルパー
    val selectTab: (String, AppDestination.Browser?) -> Unit = remember(navController, viewModel) {
        { tabId, beforeTab ->
            navController.selectTab(tabId, beforeTab)
            viewModel.selectTab(tabId)
        }
    }

    LaunchedEffect(newTabUrlFlow) {
        // タブ復元完了を待ってから外部URLを処理する（レースコンディション防止）
        setupComplete.await()
        newTabUrlFlow.collect { url ->
            val newTab = browserSessionController.createAndAppendTab(initialUrl = url)
            selectTab(newTab.tabId, null)
        }
    }

    val handleNotificationPermission: (uri: String) -> GeckoResult<Int> = { uri ->
        viewModel.handleNotificationPermission(
            uri = uri,
            onDesktopNotificationPermissionRequest = onDesktopNotificationPermissionRequest,
        )
    }

    BackHandler(enabled = navController.isLastBackHandled) {
        navController.back()
    }

    BrowserTheme(themeMode = settingsUiState.themeMode) {
        NavDisplay(
            backStack = backStack,
            onBack = { navController.back() },
            transitionSpec = {
                val default = defaultTransitionSpec<NavKey>()(this)
                val initial = initialState.entries.lastOrNull() ?: return@NavDisplay default
                val target = targetState.entries.lastOrNull() ?: return@NavDisplay default

                if (target.contentKey is AppDestination.Browser && initial.contentKey is AppDestination.Browser) {
                    return@NavDisplay ContentTransform(
                        initialContentExit = fadeOut(snap(100)),
                        targetContentEnter = fadeIn(snap(100)),
                    )
                }

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
                        LaunchedEffect(Unit) {
                            val tabId = viewModel.restoreTabs()
                            selectTab(tabId, null)
                            setupComplete.complete(Unit) // 復元完了を通知
                        }
                    }

                    is AppDestination.Browser -> navEntry(key) {
                        val browserScreenViewModel = remember(viewModel) {
                            BrowserScreenViewModel(viewModel.settingsUiState, viewModel.historyRepository)
                        }
                        BrowserScreen(
                            key = key,
                            homepageUrl = homepageUrl,
                            searchTemplate = searchTemplate,
                            backStack = backStack,
                            browserSessionController = browserSessionController,
                            viewModel = browserScreenViewModel,
                            navController = navController,
                            themeColorExtension = themeColorExtension,
                            onInstallExtensionRequest = onInstallExtensionRequest,
                            handleNotificationPermission = handleNotificationPermission,
                            onSelectTab = { tabId, beforeTab ->
                                selectTab(tabId, beforeTab)
                            },
                        )
                    }

                    AppDestination.Settings -> navEntry(key) {
                        val settingsViewModel = remember(viewModel) {
                            SettingsScreenViewModel(viewModel.settingsRepository, viewModel.settingsUiState)
                        }
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                            onOpenNotificationPermissions = {
                                backStack.add(AppDestination.NotificationPermissions)
                            },
                            onOpenHistory = { backStack.add(AppDestination.History) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.History -> navEntry(key) {
                        val historyViewModel = remember(viewModel) {
                            HistoryScreenViewModel(viewModel.historyRepository)
                        }
                        HistoryScreen(
                            viewModel = historyViewModel,
                            onNavigateToUrl = { url ->
                                val newTab = browserSessionController.createAndAppendTab(
                                    initialUrl = url,
                                )
                                navController.selectTab(newTab.tabId)
                            },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Extensions -> navEntry(key) {
                        val extensionsViewModel = remember(viewModel, browserSessionController) {
                            ExtensionsScreenViewModel(
                                runtime = viewModel.runtime,
                                onOpenExtensionSettingsRequest = { optionsPageUrl ->
                                    val tab = browserSessionController.createAndAppendTab(
                                        initialUrl = optionsPageUrl,
                                    )
                                    selectTab(tab.tabId, null)
                                },
                            )
                        }
                        ExtensionsScreen(
                            viewModel = extensionsViewModel,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.NotificationPermissions -> navEntry(key) {
                        val notificationPermissionsViewModel = remember(viewModel) {
                            NotificationPermissionsScreenViewModel(viewModel.settingsRepository, viewModel.settingsUiState)
                        }
                        NotificationPermissionsScreen(
                            viewModel = notificationPermissionsViewModel,
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
                                selectTab(tabId, null)
                            },
                            onCloseTab = { tabId ->
                                browserSessionController.closeTab(tabId)
                                if (browserSessionController.tabs.isEmpty()) {
                                    val newTab = browserSessionController.createAndAppendTab(
                                        initialUrl = homepageUrl,
                                    )
                                    selectTab(newTab.tabId, null)
                                }
                            },
                            onOpenNewTab = {
                                val newTab = browserSessionController.createAndAppendTab(
                                    initialUrl = homepageUrl,
                                )
                                selectTab(newTab.tabId, null)
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

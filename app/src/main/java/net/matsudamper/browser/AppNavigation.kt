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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import com.google.protobuf.ByteString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime

@Serializable
private sealed interface AppDestination : NavKey, java.io.Serializable {
    @Serializable
    data object Browser : AppDestination, java.io.Serializable

    @Serializable
    data object Settings : AppDestination, java.io.Serializable

    @Serializable
    data object Extensions : AppDestination, java.io.Serializable

    @Serializable
    data object Tabs : AppDestination, java.io.Serializable
}

@Composable
internal fun BrowserApp(
    runtime: GeckoRuntime,
    browserSessionController: BrowserSessionController,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings
        .collectAsState(initial = null)
    val currentSettings = settings ?: return
    val homepageUrl = currentSettings.resolvedHomepageUrl()
    val searchTemplate = currentSettings.resolvedSearchTemplate()

    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(AppDestination.Browser)
    var tabPersistenceSignal by remember { mutableLongStateOf(0L) }

    val persistedTabs = remember(currentSettings.tabStatesList) {
        currentSettings.tabStatesList.map { tabState ->
            PersistedBrowserTab(
                url = tabState.url,
                sessionState = tabState.sessionState,
                title = tabState.title,
                previewImageWebp = tabState.previewImageWebp.toByteArray(),
            )
        }
    }

    LaunchedEffect(tabPersistenceSignal) {
        if (tabPersistenceSignal == 0L) {
            return@LaunchedEffect
        }
        delay(250L)
        settingsRepository.updateTabStates(
            tabs = browserSessionController.exportPersistedTabs().map { tab ->
                PersistedTabState(
                    url = tab.url,
                    sessionState = tab.sessionState,
                    title = tab.title,
                    previewImageWebp = ByteString.copyFrom(tab.previewImageWebp),
                )
            },
            selectedTabIndex = browserSessionController.selectedTabIndex,
        )
    }

    LaunchedEffect(
        browserSessionController,
        homepageUrl,
        persistedTabs,
        currentSettings.selectedTabIndex
    ) {
        browserSessionController.ensureInitialPageLoaded(
            homepageUrl = homepageUrl,
            persistedTabs = persistedTabs,
            persistedSelectedTabIndex = currentSettings.selectedTabIndex,
        )
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
    }

    BrowserTheme(themeMode = currentSettings.themeMode) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = {
                val default = defaultTransitionSpec<NavKey>()(this)
                val initial = initialState.entries.lastOrNull()
                    ?: return@NavDisplay default
                val target = targetState.entries.lastOrNull()
                    ?: return@NavDisplay default

                if (target.contentKey is AppDestination.Tabs && initial.contentKey is AppDestination.Browser) {
                    return@NavDisplay ContentTransform(
                        initialContentExit = ExitTransition.None,
                        targetContentEnter = slideIn {
                            IntOffset(
                                x = 0,
                                y = -it.height / 2,
                            )
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
                    AppDestination.Browser -> navEntry(
                        key = key,
                    ) {
                        val selectedTab = browserSessionController.selectedTab
                        if (selectedTab != null) {
                            val tabs = browserSessionController.tabs

                            GeckoBrowserTab(
                                tabId = selectedTab.id,
                                session = selectedTab.session,
                                initialUrl = selectedTab.currentUrl,
                                homepageUrl = homepageUrl,
                                searchTemplate = searchTemplate,
                                translationProvider = currentSettings.translationProvider,
                                tabCount = tabs.size,
                                onInstallExtensionRequest = onInstallExtensionRequest,
                                onDesktopNotificationPermissionRequest = onDesktopNotificationPermissionRequest,
                                onOpenSettings = {
                                    backStack.add(AppDestination.Settings)
                                },
                                onOpenTabs = {
                                    backStack.add(AppDestination.Tabs)
                                },
                                onOpenNewSessionRequest = { uri ->
                                    val newTab = browserSessionController.createTabForNewSession(
                                        initialUrl = uri,
                                    )
                                    browserSessionController.selectTab(newTab.id)
                                    tabPersistenceSignal++
                                    newTab.session
                                },
                                onCurrentPageUrlChange = { currentUrl ->
                                    browserSessionController.updateTabUrl(
                                        tabId = selectedTab.id,
                                        url = currentUrl,
                                    )
                                    tabPersistenceSignal++
                                },
                                onSessionStateChange = { sessionState ->
                                    browserSessionController.updateTabSessionState(
                                        tabId = selectedTab.id,
                                        sessionState = sessionState,
                                    )
                                    tabPersistenceSignal++
                                },
                                onTabPreviewCaptured = { previewBitmap ->
                                    browserSessionController.updateTabPreview(
                                        tabId = selectedTab.id,
                                        previewBitmap = previewBitmap,
                                    )
                                },
                                onTabTitleChange = { title ->
                                    browserSessionController.updateTabTitle(
                                        tabId = selectedTab.id,
                                        title = title,
                                    )
                                    tabPersistenceSignal++
                                },
                            )
                        }
                    }

                    AppDestination.Settings -> navEntry(key) {
                        val latestSettings by settingsRepository.settings
                            .collectAsState(initial = currentSettings)
                        SettingsScreen(
                            settings = latestSettings,
                            onSettingsChange = { newSettings ->
                                scope.launch { settingsRepository.updateSettings(newSettings) }
                            },
                            onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Extensions -> navEntry(key) {
                        ExtensionsScreen(
                            runtime = runtime,
                            onBack = { backStack.removeLastOrNull() },
                            onOpenExtensionSettings = { optionsPageUrl ->
                                browserSessionController.selectedTab?.session?.loadUri(
                                    optionsPageUrl
                                )
                                backStack.removeLastOrNull()
                            },
                        )
                    }

                    AppDestination.Tabs -> navEntry(key) {
                        TabsScreen(
                            tabs = browserSessionController.tabs,
                            selectedTabId = browserSessionController.selectedTab?.id,
                            onSelectTab = { tabId ->
                                browserSessionController.selectTab(tabId)
                                tabPersistenceSignal++
                                backStack.removeLastOrNull()
                            },
                            onCloseTab = { tabId ->
                                browserSessionController.closeTab(tabId)
                                if (browserSessionController.tabs.isEmpty()) {
                                    val newTab = browserSessionController.createTab(
                                        initialUrl = homepageUrl,
                                    )
                                    browserSessionController.selectTab(newTab.id)
                                }
                                tabPersistenceSignal++
                            },
                            onOpenNewTab = {
                                val newTab = browserSessionController.createTab(
                                    initialUrl = homepageUrl,
                                )
                                browserSessionController.selectTab(newTab.id)
                                tabPersistenceSignal++
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

private fun <T : NavKey> AnimatedContentTransitionScope<Scene<T>>.popTransition(heightProvider: (Int) -> Int): ContentTransform {
    val default = defaultPopTransitionSpec<T>()(this)
    val initial = initialState.entries.lastOrNull()
        ?: return default
    val target = targetState.entries.lastOrNull()
        ?: return default

    if (initial.contentKey is AppDestination.Tabs && target.contentKey is AppDestination.Browser) {
        return ContentTransform(
            initialContentExit = slideOut {
                IntOffset(
                    x = 0,
                    y = heightProvider(it.height),
                )
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

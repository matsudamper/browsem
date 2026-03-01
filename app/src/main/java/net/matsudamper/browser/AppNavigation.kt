package net.matsudamper.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import org.mozilla.geckoview.GeckoRuntime
import kotlinx.coroutines.delay

@Serializable
private sealed interface AppDestination : NavKey {
    @Serializable
    data object Browser : AppDestination

    @Serializable
    data object Settings : AppDestination

    @Serializable
    data object Extensions : AppDestination
}

@Composable
internal fun BrowserApp(
    runtime: GeckoRuntime,
    onInstallExtensionRequest: (String) -> Unit,
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings
        .collectAsState(initial = BrowserSettings.getDefaultInstance())
    val browserSessionController = rememberBrowserSessionController(runtime)
    val homepageUrl = settings.resolvedHomepageUrl()
    val searchTemplate = settings.resolvedSearchTemplate()

    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(AppDestination.Browser)
    var tabPersistenceSignal by remember { mutableLongStateOf(0L) }

    val persistedTabs = remember(settings.tabStatesList) {
        settings.tabStatesList.map { tabState ->
            PersistedBrowserTab(
                url = tabState.url,
                sessionState = tabState.sessionState,
                title = tabState.title,
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
                )
            },
            selectedTabIndex = browserSessionController.selectedTabIndex,
        )
    }

    LaunchedEffect(browserSessionController, homepageUrl, persistedTabs, settings.selectedTabIndex) {
        browserSessionController.ensureInitialPageLoaded(
            homepageUrl = homepageUrl,
            persistedTabs = persistedTabs,
            persistedSelectedTabIndex = settings.selectedTabIndex,
        )
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
    }

    BrowserTheme(themeMode = settings.themeMode) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = { key: NavKey ->
                when (key) {
                    AppDestination.Browser -> NavEntry<NavKey>(key) {
                        val selectedTab = browserSessionController.selectedTab
                        if (selectedTab != null) {
                            var tabsVisible by rememberSaveable { mutableStateOf(false) }
                            val tabs = browserSessionController.tabs

                            BackHandler(enabled = tabsVisible) {
                                tabsVisible = false
                            }

                            Box(
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                GeckoBrowserTab(
                                    tabId = selectedTab.id,
                                    session = selectedTab.session,
                                    initialUrl = selectedTab.currentUrl,
                                    homepageUrl = homepageUrl,
                                    searchTemplate = searchTemplate,
                                    tabCount = tabs.size,
                                    onInstallExtensionRequest = onInstallExtensionRequest,
                                    onOpenSettings = {
                                        backStack.add(AppDestination.Settings)
                                    },
                                    onOpenTabs = {
                                        tabsVisible = true
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

                                AnimatedVisibility(
                                    visible = tabsVisible,
                                    enter = fadeIn(animationSpec = tween(durationMillis = 220)),
                                    exit = fadeOut(animationSpec = tween(durationMillis = 220)),
                                ) {
                                    TabsScreen(
                                        tabs = tabs,
                                        selectedTabId = selectedTab.id,
                                        onSelectTab = { tabId ->
                                            browserSessionController.selectTab(tabId)
                                            tabPersistenceSignal++
                                            tabsVisible = false
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
                                            tabsVisible = false
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surface),
                                    )
                                }
                            }
                        }
                    }

                    AppDestination.Settings -> NavEntry<NavKey>(key) {
                        SettingsScreen(
                            settings = settings,
                            onSettingsChange = { newSettings ->
                                scope.launch { settingsRepository.updateSettings(newSettings) }
                            },
                            onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Extensions -> NavEntry<NavKey>(key) {
                        ExtensionsScreen(
                            runtime = runtime,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    else -> error("Unknown destination: $key")
                }
            },
        )
    }
}

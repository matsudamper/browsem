package net.matsudamper.browser

import androidx.activity.compose.BackHandler
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
    data class Browser(val tabId: Long) : AppDestination, java.io.Serializable

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
    externalNewTabUrl: String?,
    onExternalNewTabConsumed: () -> Unit,
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

    // 初期タブを同期的に確保し、backStack の初期値として使用する
    val initialTabId = remember(browserSessionController, homepageUrl, persistedTabs, currentSettings.selectedTabIndex) {
        browserSessionController.ensureInitialPageLoaded(
            homepageUrl = homepageUrl,
            persistedTabs = persistedTabs,
            persistedSelectedTabIndex = currentSettings.selectedTabIndex,
        )
    }

    var tabPersistenceSignal by remember { mutableLongStateOf(0L) }
    val backStack = rememberNavBackStack(AppDestination.Browser(initialTabId))

    // backStack から現在表示中のタブIDを取得するヘルパー
    fun currentBrowserTabId(): Long? =
        backStack.filterIsInstance<AppDestination.Browser>().lastOrNull()?.tabId

    LaunchedEffect(tabPersistenceSignal) {
        if (tabPersistenceSignal == 0L) {
            return@LaunchedEffect
        }
        delay(250L)
        val currentTabId = currentBrowserTabId() ?: return@LaunchedEffect
        settingsRepository.updateTabStates(
            tabs = browserSessionController.exportPersistedTabs(currentTabId).map { tab ->
                PersistedTabState(
                    url = tab.url,
                    sessionState = tab.sessionState,
                    title = tab.title,
                    previewImageWebp = ByteString.copyFrom(tab.previewImageWebp),
                )
            },
            selectedTabIndex = browserSessionController.selectedTabIndex(currentTabId),
        )
    }

    // 外部URL（Intent など）から新タブを開く要求を処理する
    LaunchedEffect(externalNewTabUrl) {
        val url = externalNewTabUrl ?: return@LaunchedEffect
        val newTab = browserSessionController.createTab(url)
        backStack.removeAll { it is AppDestination.Browser }
        backStack.add(AppDestination.Browser(newTab.id))
        onExternalNewTabConsumed()
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
            popTransitionSpec = {
                val default = defaultPopTransitionSpec<NavKey>()(this)
                val initial = initialState.entries.lastOrNull()
                    ?: return@NavDisplay default
                val target = targetState.entries.lastOrNull()
                    ?: return@NavDisplay default

                if (initial.contentKey is AppDestination.Tabs && target.contentKey is AppDestination.Browser) {
                    return@NavDisplay ContentTransform(
                        initialContentExit = slideOut {
                            IntOffset(
                                x = 0,
                                y = -it.height,
                            )
                        },
                        targetContentEnter = EnterTransition.None,
                    )
                }

                if (target.contentKey is AppDestination.Browser) {
                    return@NavDisplay ContentTransform(
                        initialContentExit = ExitTransition.None,
                        targetContentEnter = EnterTransition.None,
                    )
                }

                default
            },
            entryProvider = { key: NavKey ->
                when {
                    key is AppDestination.Browser -> navEntry(key) {
                        val tab = browserSessionController.tabs.firstOrNull { it.id == key.tabId }
                        if (tab != null) {
                            val tabs = browserSessionController.tabs

                            GeckoBrowserTab(
                                tabId = tab.id,
                                session = tab.session,
                                initialUrl = tab.currentUrl,
                                homepageUrl = homepageUrl,
                                searchTemplate = searchTemplate,
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
                                    // 現在の Browser エントリを新しいタブで置き換える
                                    backStack.removeAll { it is AppDestination.Browser }
                                    backStack.add(AppDestination.Browser(newTab.id))
                                    tabPersistenceSignal++
                                    newTab.session
                                },
                                onCurrentPageUrlChange = { currentUrl ->
                                    browserSessionController.updateTabUrl(
                                        tabId = tab.id,
                                        url = currentUrl,
                                    )
                                    tabPersistenceSignal++
                                },
                                onSessionStateChange = { sessionState ->
                                    browserSessionController.updateTabSessionState(
                                        tabId = tab.id,
                                        sessionState = sessionState,
                                    )
                                    tabPersistenceSignal++
                                },
                                onTabPreviewCaptured = { previewBitmap ->
                                    browserSessionController.updateTabPreview(
                                        tabId = tab.id,
                                        previewBitmap = previewBitmap,
                                    )
                                },
                                onTabTitleChange = { title ->
                                    browserSessionController.updateTabTitle(
                                        tabId = tab.id,
                                        title = title,
                                    )
                                    tabPersistenceSignal++
                                },
                            )
                        }
                    }

                    key == AppDestination.Settings -> navEntry(key) {
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

                    key == AppDestination.Extensions -> navEntry(key) {
                        ExtensionsScreen(
                            runtime = runtime,
                            onBack = { backStack.removeLastOrNull() },
                            onOpenExtensionSettings = { optionsPageUrl ->
                                // 現在表示中の Browser タブのセッションで開く
                                val currentTabId = currentBrowserTabId()
                                val currentTab = browserSessionController.tabs.firstOrNull { it.id == currentTabId }
                                currentTab?.session?.loadUri(optionsPageUrl)
                                backStack.removeLastOrNull()
                            },
                        )
                    }

                    key == AppDestination.Tabs -> navEntry(key) {
                        val currentTabId = currentBrowserTabId()
                        TabsScreen(
                            tabs = browserSessionController.tabs,
                            selectedTabId = currentTabId,
                            onSelectTab = { tabId ->
                                // Browser エントリを選択タブで置き換えて Tabs を閉じる
                                backStack.removeAll { it is AppDestination.Browser }
                                backStack.add(AppDestination.Browser(tabId))
                                backStack.removeAll { it == AppDestination.Tabs }
                                tabPersistenceSignal++
                            },
                            onCloseTab = { tabId ->
                                val nextTabId = browserSessionController.closeTab(tabId)
                                if (nextTabId == null) {
                                    // タブが空になったので新規タブを作成
                                    val newTab = browserSessionController.createTab(
                                        initialUrl = homepageUrl,
                                    )
                                    backStack.removeAll { it is AppDestination.Browser }
                                    backStack.add(AppDestination.Browser(newTab.id))
                                } else if (tabId == currentTabId) {
                                    // 表示中のタブが閉じられた場合は次のタブに切り替え
                                    backStack.removeAll { it is AppDestination.Browser }
                                    backStack.add(AppDestination.Browser(nextTabId))
                                }
                                tabPersistenceSignal++
                            },
                            onOpenNewTab = {
                                val newTab = browserSessionController.createTab(
                                    initialUrl = homepageUrl,
                                )
                                backStack.removeAll { it is AppDestination.Browser }
                                backStack.add(AppDestination.Browser(newTab.id))
                                backStack.removeAll { it == AppDestination.Tabs }
                                tabPersistenceSignal++
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
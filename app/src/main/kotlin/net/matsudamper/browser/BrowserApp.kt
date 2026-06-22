package net.matsudamper.browser

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.extractSiteHost
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.BrowserNavDestination
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.screen.backup.BackupProgressViewModel
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreenViewModel
import net.matsudamper.browser.ui.browser.BrowserScreen
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.downloads.DownloadManagementScreen
import net.matsudamper.browser.ui.extensions.ExtensionsScreen
import net.matsudamper.browser.ui.history.HistoryScreen
import net.matsudamper.browser.ui.settings.BackupProgressScreen
import net.matsudamper.browser.ui.settings.BackupProgressUiState
import net.matsudamper.browser.ui.settings.SettingsScreen
import net.matsudamper.browser.ui.settings.SiteSettingsScreen
import net.matsudamper.browser.ui.tabs.TabsScreen
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoRuntime

// ──────────────────────────────────────────────────────────────
// 本体ブラウザ用エントリポイント (MainActivity から呼ばれる)
// ──────────────────────────────────────────────────────────────

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<NewTabRequest>,
    openDownloadsFlow: Flow<Unit>,
    onInstallExtensionRequest: (String) -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
) {
    val currentUiState by viewModel.uiState.collectAsState()
    val uiState = currentUiState
    if (uiState == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        BrowserTheme(themeMode = uiState.themeMode) {
            // 履歴・ダウンロード画面から URL を開く際、内側ナビのタブ選択が必要。
            // MainBrowserContent が selectTab を生成した後にセットされる。
            val selectTabRef = remember { SelectTabRef() }
            BrowserAppShell(
                browserTabController = viewModel.browserTabController,
                browserSessionLifecycleController = viewModel.browserSessionLifecycleController,
                runtime = viewModel.runtime,
                onNavigateToUrl = { url ->
                    val tabId = UUID.randomUUID().toString()
                    val newTab = viewModel.browserTabController.createAndAppendTab(
                        tabId = tabId,
                        initialUrl = url,
                    )
                    selectTabRef.selectTab?.invoke(newTab.tabId, null)
                },
            ) { outerBackStack ->
                val outerNavActions = remember(outerBackStack) { OuterNavActions(outerBackStack) }
                MainBrowserContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    newTabUrlFlow = newTabUrlFlow,
                    openDownloadsFlow = openDownloadsFlow,
                    onInstallExtensionRequest = onInstallExtensionRequest,
                    onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
                    outerNavActions = outerNavActions,
                    selectTabRef = selectTabRef,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 外側シェル — 全モード共有
// 全画面系の navEntry (Settings, SiteSettings, …) はここに集約。
// rootContent が本体/App/CustomTab のモード別内容を描画する。
// ──────────────────────────────────────────────────────────────

@Composable
/**
 * @param onNavigateToUrl 履歴やダウンロードからURLを開く際のコールバック。
 *   呼び出し側がタブ作成と内側ナビの更新を行う。null の場合は導線を非表示にする。
 */
internal fun BrowserAppShell(
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    runtime: GeckoRuntime,
    onNavigateToUrl: (suspend (url: String) -> Unit)? = null,
    rootContent: @Composable (outerBackStack: MutableList<NavKey>) -> Unit,
) {
    val outerBackStack = rememberNavBackStack(AppDestination.Root)
    val settingsRepository: SettingsRepository = koinInject()
    val historyRepository: HistoryRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    NavDisplay(
        backStack = outerBackStack,
        onBack = { outerBackStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key: NavKey ->
            when (key) {
                AppDestination.Root -> navEntry(key) {
                    rootContent(outerBackStack)
                }

                AppDestination.Settings -> navEntry(key) {
                    val settingsViewModel = composeViewModel(initializer = {
                        SettingsScreenViewModel(settingsRepository)
                    })
                    val settingsUiState by settingsViewModel.uiState.collectAsState()
                    LaunchedEffect(settingsViewModel) {
                        settingsViewModel.eventHandler.receiveAsFlow().collect { handler ->
                            handler(object : SettingsScreenViewModel.Event {
                                override fun onOpenMockLocationOnMap() {
                                    val settingsUiState = settingsViewModel.uiState.value ?: return
                                    val parts = settingsUiState.mockLocationInput.split(",")
                                    if (parts.size != 2) return
                                    val lat = parts[0].trim().toDoubleOrNull() ?: return
                                    val lng = parts[1].trim().toDoubleOrNull() ?: return
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("geo:$lat,$lng?q=$lat,$lng"),
                                    )
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: ActivityNotFoundException) {
                                    }
                                }

                                override fun onNavigateToBackupProgress(isImport: Boolean) {
                                    outerBackStack.add(AppDestination.BackupProgress(isImport))
                                }
                            })
                        }
                    }
                    settingsUiState?.let { uiState ->
                        SettingsScreen(
                            uiState = uiState,
                            onOpenExtensions = { outerBackStack.add(AppDestination.Extensions) },
                            onOpenHistory = { outerBackStack.add(AppDestination.History) },
                            onBack = { outerBackStack.removeLastOrNull() },
                        )
                    }
                }

                is AppDestination.SiteSettings -> navEntry(key) {
                    val siteSettingsRepository: SiteSettingsRepository = koinInject()
                    val geckoRuntime: GeckoRuntime = koinInject()
                    val publicSuffixList: PublicSuffixList = koinInject()
                    val siteSettingsViewModel = composeViewModel(initializer = {
                        SiteSettingsScreenViewModel(
                            host = key.host,
                            siteSettingsRepository = siteSettingsRepository,
                            geckoRuntime = geckoRuntime,
                            publicSuffixList = publicSuffixList,
                            securityInfo = key.tabId
                                ?.let { browserTabController.findTab(it) }
                                ?.securityInfo,
                        )
                    })
                    val locationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { results ->
                        siteSettingsViewModel.onLocationPermissionResult(results.values.any { it })
                    }
                    LaunchedEffect(siteSettingsViewModel) {
                        siteSettingsViewModel.eventHandler.receiveAsFlow().collect { handler ->
                            handler(object : SiteSettingsScreenViewModel.Event {
                                override fun onRequestLocationPermission() {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                }
                            })
                        }
                    }
                    val siteSettingsUiState by siteSettingsViewModel.uiState.collectAsState()
                    SiteSettingsScreen(
                        uiState = siteSettingsUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                AppDestination.History -> navEntry(key) {
                    val historyViewModel = composeViewModel(initializer = {
                        HistoryScreenViewModel(historyRepository)
                    })
                    val historyUiState by historyViewModel.uiState.collectAsState()
                    LaunchedEffect(historyViewModel) {
                        historyViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : HistoryScreenViewModel.Event {
                                override fun navigateToUrl(url: String) {
                                    scope.launch {
                                        onNavigateToUrl?.invoke(url)
                                        while (outerBackStack.size > 1) {
                                            outerBackStack.removeLastOrNull()
                                        }
                                    }
                                }
                            })
                        }
                    }
                    HistoryScreen(
                        uiState = historyUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                AppDestination.Extensions -> navEntry(key) {
                    val extensionsViewModel = composeViewModel(initializer = {
                        ExtensionsScreenViewModel(runtime = runtime)
                    })
                    val extensionsUiState by extensionsViewModel.uiState.collectAsState()
                    LaunchedEffect(extensionsViewModel) {
                        extensionsViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : ExtensionsScreenViewModel.Event {
                                override fun navigateToExtensionSettings(url: String) {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(url),
                                            context,
                                            CustomTabActivity::class.java,
                                        )
                                    )
                                }
                            })
                        }
                    }
                    ExtensionsScreen(
                        uiState = extensionsUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                AppDestination.Downloads -> navEntry(key) {
                    val downloadsViewModel = composeViewModel(initializer = {
                        DownloadManagementScreenViewModel(context.applicationContext as Application)
                    })
                    val downloadsUiState by downloadsViewModel.uiState.collectAsState()
                    LaunchedEffect(downloadsViewModel) {
                        downloadsViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : DownloadManagementScreenViewModel.Event {
                                override fun navigateToUrl(url: String) {
                                    scope.launch {
                                        onNavigateToUrl?.invoke(url)
                                        while (outerBackStack.size > 1) {
                                            outerBackStack.removeLastOrNull()
                                        }
                                    }
                                }
                            })
                        }
                    }
                    DownloadManagementScreen(
                        uiState = downloadsUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                is AppDestination.BackupProgress -> navEntry(key) {
                    val backupRepository: BackupRepository = koinInject()
                    val backupViewModel = composeViewModel(initializer = {
                        BackupProgressViewModel(key.isImport, backupRepository)
                    })
                    val backupUiState by backupViewModel.uiState.collectAsState()

                    val pausedTabIds = remember { mutableSetOf<String>() }

                    LaunchedEffect(browserTabController, browserSessionLifecycleController) {
                        browserTabController.tabStoreState.collect {
                            browserTabController.tabs.forEach { tab ->
                                if (pausedTabIds.add(tab.tabId)) {
                                    browserSessionLifecycleController.pauseSession(tab)
                                }
                            }
                        }
                    }

                    DisposableEffect(browserTabController, browserSessionLifecycleController) {
                        onDispose {
                            browserTabController.tabs
                                .filter { it.tabId in pausedTabIds }
                                .forEach { tab ->
                                    browserSessionLifecycleController.resumeSession(tab)
                                }
                        }
                    }

                    val isInProgress = backupUiState.phase is BackupProgressUiState.Phase.InProgress
                    LaunchedEffect(isInProgress) {
                        if (isInProgress) {
                            WorkManager.getInstance(context)
                                .cancelAllWorkByTag(DownloadWorker.TAG_DOWNLOAD)
                        }
                    }

                    val exportLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument(BackupRepository.MIME_TYPE),
                    ) { uri ->
                        if (uri != null) {
                            backupViewModel.startWithUri(uri)
                        } else {
                            outerBackStack.removeLastOrNull()
                        }
                    }
                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            backupViewModel.startWithUri(uri)
                        } else {
                            outerBackStack.removeLastOrNull()
                        }
                    }

                    LaunchedEffect(backupViewModel) {
                        backupViewModel.eventHandler.receiveAsFlow().collect { handler ->
                            handler(object : BackupProgressViewModel.Event {
                                override fun onRequestFilePicker() {
                                    if (key.isImport) {
                                        importLauncher.launch(
                                            arrayOf(BackupRepository.MIME_TYPE, "application/octet-stream", "*/*"),
                                        )
                                    } else {
                                        exportLauncher.launch(buildBackupFileName())
                                    }
                                }

                                override fun onRestartApp() {
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                }

                                override fun onNavigateBack() {
                                    outerBackStack.removeLastOrNull()
                                }
                            })
                        }
                    }

                    BackupProgressScreen(
                        uiState = backupUiState,
                    )
                }

                else -> error("Unknown destination: $key")
            }
        },
    )
}

// ──────────────────────────────────────────────────────────────
// 本体ブラウザ — 内側ナビ (Setup / Browser / Tabs)
// タブ切替・beforeTab 履歴・NavController はここに閉じ込める。
// ──────────────────────────────────────────────────────────────

@Stable
internal class OuterNavActions(private val backStack: MutableList<NavKey>) {
    fun add(destination: AppDestination) { backStack.add(destination) }
    fun addIfAbsent(destination: AppDestination) {
        if (backStack.none { it == destination }) backStack.add(destination)
    }
}

@Stable
internal class SelectTabRef {
    var selectTab: ((String, BrowserNavDestination.Browser?) -> Unit)? = null
}

@Composable
private fun MainBrowserContent(
    uiState: BrowserAppUiState,
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<NewTabRequest>,
    openDownloadsFlow: Flow<Unit>,
    onInstallExtensionRequest: (String) -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
    outerNavActions: OuterNavActions,
    selectTabRef: SelectTabRef,
) {
    val browserTabController = viewModel.browserTabController
    val browserSessionLifecycleController = viewModel.browserSessionLifecycleController
    val themeColorExtension = viewModel.themeColorExtension
    val mediaWebExtension = viewModel.mediaWebExtension

    val settingsRepository: SettingsRepository = koinInject()
    val historyRepository: HistoryRepository = koinInject()
    val webSuggestionRepository: WebSuggestionRepository = koinInject()
    val tabGroupRepository: TabGroupRepository = koinInject()

    val innerBackStack = rememberNavBackStack(BrowserNavDestination.Setup)
    val navController = remember(innerBackStack) { NavController(backStack = innerBackStack) }
    val setupComplete = viewModel.setupComplete

    val selectTab: (String, BrowserNavDestination.Browser?) -> Unit = remember(navController, browserTabController) {
        { tabId, beforeTab ->
            navController.selectTab(tabId, beforeTab)
            browserTabController.selectTab(tabId)
        }
    }
    selectTabRef.selectTab = selectTab

    val scope = rememberCoroutineScope()

    suspend fun assignTabToOpenerGroup(tabId: String, openerTabId: String) {
        val openerGroupId = tabGroupRepository.observeTabGroupAssignments()
            .first()
            .find { it.tabId == openerTabId }
            ?.groupId
            ?.takeIf { it.isNotEmpty() }
            ?.let { TabGroupId(it) }
        if (openerGroupId != null) {
            tabGroupRepository.assignTabToGroup(tabId, openerGroupId)
        }
    }

    LaunchedEffect(openDownloadsFlow) {
        openDownloadsFlow.onEach {
            outerNavActions.addIfAbsent(AppDestination.Downloads)
        }.launchIn(this)
    }

    LaunchedEffect(viewModel) {
        viewModel.eventHandler.receiveAsFlow().collect { event ->
            event(object : BrowserViewModel.Event {
                override fun onTabsRestored(tabId: String) {
                    when {
                        innerBackStack.firstOrNull() is BrowserNavDestination.Setup -> {
                            selectTab(tabId, null)
                        }
                        else -> {
                            val currentBrowserTab = innerBackStack.filterIsInstance<BrowserNavDestination.Browser>().lastOrNull()
                            if (currentBrowserTab == null || browserTabController.findTab(currentBrowserTab.tabId) == null) {
                                selectTab(tabId, null)
                            }
                        }
                    }
                }
            })
        }
    }

    LaunchedEffect(newTabUrlFlow) {
        setupComplete.await()
        newTabUrlFlow.collect { request ->
            val tabId = UUID.randomUUID().toString()
            val defaultGroupId = tabGroupRepository.getDefaultGroupId()
            if (defaultGroupId != null) {
                tabGroupRepository.assignTabToGroup(tabId, defaultGroupId)
            }
            val newTab = browserTabController.createAndAppendTab(
                tabId = tabId,
                initialUrl = request.url,
                restoredSessionState = request.sessionState,
                insertAfterSelectedTab = false,
            )
            viewModel.registerExternalTab(newTab.tabId)
            selectTab(newTab.tabId, null)
        }
    }

    BackHandler(enabled = navController.isLastBackHandled) {
        navController.back()
    }

    NavDisplay(
        backStack = innerBackStack,
        onBack = { navController.back() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = {
            val default = defaultTransitionSpec<NavKey>()(this)
            val initial = initialState.entries.lastOrNull() ?: return@NavDisplay default
            val target = targetState.entries.lastOrNull() ?: return@NavDisplay default

            if (target.contentKey is BrowserNavDestination.Browser && initial.contentKey is BrowserNavDestination.Browser) {
                return@NavDisplay ContentTransform(
                    initialContentExit = fadeOut(snap(100)),
                    targetContentEnter = fadeIn(snap(100)),
                )
            }

            if (target.contentKey is BrowserNavDestination.Tabs && initial.contentKey is BrowserNavDestination.Browser) {
                return@NavDisplay ContentTransform(
                    initialContentExit = ExitTransition.None,
                    targetContentEnter = slideIn {
                        IntOffset(x = 0, y = -it.height / 2)
                    },
                )
            }

            if (initial.contentKey is BrowserNavDestination.Browser) {
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
                is BrowserNavDestination.Setup -> navEntry(key) {
                }

                is BrowserNavDestination.Browser -> navEntry(key) {
                    val browserTabsFlow = remember(browserTabController) {
                        browserTabController.tabStoreState
                            .map { browserTabController.tabs.toList() }
                            .distinctUntilChanged()
                    }
                    val browserScreenViewModel = remember(key.tabId, tabGroupRepository, browserTabsFlow) {
                        BrowserScreenViewModel(
                            historyRepository = historyRepository,
                            settingsRepository = settingsRepository,
                            webSuggestionRepository = webSuggestionRepository,
                            tabGroupRepository = tabGroupRepository,
                            browserTabsFlow = browserTabsFlow,
                            screenTabId = key.tabId,
                        )
                    }
                    DisposableEffect(key.tabId) {
                        onDispose { browserScreenViewModel.close() }
                    }
                    val browserScreenUiState by browserScreenViewModel.uiState.collectAsState()
                    BrowserScreen(
                        tabId = key.tabId,
                        homepageUrl = uiState.homepageUrl,
                        uiState = browserScreenUiState,
                        browserTabController = browserTabController,
                        onSelectTab = { tabId ->
                            selectTab(tabId, null)
                        },
                        previewHeaderContent = { modifier, tab, tabCount ->
                            BrowserToolbar(
                                modifier = modifier,
                                toolbarColor = null,
                                isFocused = false,
                                onLongClickUrl = {},
                                tabCount = tabCount,
                                onOpenTabs = {},
                                toolbarMenu = {},
                                gestureState = null,
                                updateVisibleMenu = {},
                                canGoForward = false,
                                onForward = {},
                                canGoBack = false,
                                onBack = {},
                                onRefresh = {},
                                onSuperRefresh = {},
                                onTranslatePage = {},
                                onLongPressHistory = {},
                                urlInputState = UrlInputState(
                                    value = tab.currentUrl,
                                    onValueChange = {},
                                    onSubmit = {},
                                    onFocusChanged = {},
                                    enableSuggest = false,
                                    scrollEnabled = false,
                                ),
                            )
                        },
                        browserTabContent = { modifier, selectedTab, tabCount, onToolbarHorizontalDrag, onToolbarDragEnd ->
                            GeckoBrowserTab(
                                modifier = modifier,
                                browserTab = selectedTab,
                                homepageUrl = uiState.homepageUrl,
                                searchTemplate = uiState.searchTemplate,
                                translationProvider = uiState.translationProvider,
                                themeColorExtension = themeColorExtension,
                                mediaWebExtension = mediaWebExtension,
                                tabCount = tabCount,
                                onInstallExtensionRequest = onInstallExtensionRequest,
                                onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
                                onOpenSettings = { outerNavActions.add(AppDestination.Settings) },
                                onOpenDownloads = {
                                    outerNavActions.addIfAbsent(AppDestination.Downloads)
                                },
                                onOpenSiteSettings = { currentUrl ->
                                    val host = extractSiteHost(currentUrl)
                                    if (host != null) {
                                        outerNavActions.add(
                                            AppDestination.SiteSettings(
                                                host = host,
                                                tabId = key.tabId,
                                            ),
                                        )
                                    }
                                },
                                onOpenTabs = { innerBackStack.add(BrowserNavDestination.Tabs) },
                                browserSessionLifecycleController = browserSessionLifecycleController,
                                onOpenNewSessionRequest = { uri ->
                                    val newTab = browserTabController.createTabForNewSession(
                                        initialUrl = uri,
                                        openerTabId = key.tabId,
                                    )
                                    scope.launch {
                                        assignTabToOpenerGroup(newTab.tabId, key.tabId)
                                    }
                                    selectTab(newTab.tabId, key)
                                    newTab.session
                                },
                                onOpenNewTabRequest = { uri, referrerUrl ->
                                    scope.launch {
                                        val tabId = UUID.randomUUID().toString()
                                        assignTabToOpenerGroup(tabId, key.tabId)
                                        val newTab = browserTabController.createAndAppendTab(
                                            tabId = tabId,
                                            initialUrl = uri,
                                            openerTabId = key.tabId,
                                            initialReferrerUrl = referrerUrl,
                                        )
                                        selectTab(newTab.tabId, key)
                                    }
                                },
                                onCloseTab = {
                                    val targetTabId = browserTabController.closeTab(key.tabId)
                                    if (targetTabId != null) {
                                        selectTab(targetTabId, null)
                                    }
                                },
                                onHistoryRecord = browserScreenUiState.callbacks::onHistoryRecord,
                                onHistoryTitleUpdate = browserScreenUiState.callbacks::onHistoryTitleUpdate,
                                urlBarSuggestions = browserScreenUiState.urlBarSuggestions,
                                onUrlInputChanged = browserScreenUiState.callbacks::onUrlInputChanged,
                                onToolbarHorizontalDrag = onToolbarHorizontalDrag,
                                onToolbarDragEnd = onToolbarDragEnd,
                            )
                        },
                    )
                }

                BrowserNavDestination.Tabs -> navEntry(key) {
                    val tabsViewModel = composeViewModel(initializer = {
                        TabsScreenViewModel(
                            tabStore = browserTabController,
                            tabGroupRepository = tabGroupRepository,
                            playingTabIds = mediaWebExtension.playingTabIds,
                        )
                    })
                    val tabsUiState by tabsViewModel.uiState.collectAsState()
                    DisposableEffect(Unit) {
                        onDispose { navController.disposeTabs() }
                    }
                    LaunchedEffect(tabsViewModel) {
                        tabsViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : TabsScreenViewModel.Event {
                                override fun onTabClosed(closedTabId: String, nextSelectedTabId: String?) {
                                    val wasCurrentBrowserTab = navController.getSelectedTab() == closedTabId
                                    if (nextSelectedTabId == null) {
                                        scope.launch {
                                            val newTab = viewModel.createTabWithHomepage(
                                                tabId = UUID.randomUUID().toString(),
                                            )
                                            selectTab(newTab.tabId, null)
                                        }
                                    } else if (wasCurrentBrowserTab) {
                                        navController.replaceCurrentBrowserTab(nextSelectedTabId)
                                    }
                                }

                                override fun selectTab(tabId: String) {
                                    browserTabController.selectTab(tabId)
                                    navController.replaceCurrentBrowserTab(tabId)
                                }
                            })
                        }
                    }
                    TabsScreen(
                        uiState = tabsUiState,
                        onSelectTab = { tabId ->
                            selectTab(tabId, null)
                        },
                        onOpenNewTab = { currentGroupId: TabGroupId? ->
                            scope.launch {
                                val tabId = UUID.randomUUID().toString()
                                if (currentGroupId != null) {
                                    tabGroupRepository.assignTabToGroup(tabId, currentGroupId)
                                }
                                val newTab = viewModel.createTabWithHomepage(
                                    tabId = tabId,
                                    insertAfterSelectedTab = false,
                                )
                                selectTab(newTab.tabId, null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }

                else -> error("Unknown inner destination: $key")
            }
        },
    )
}

// ──────────────────────────────────────────────────────────────
// ユーティリティ
// ──────────────────────────────────────────────────────────────

private fun buildBackupFileName(): String {
    val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    return "browsem-backup-${formatter.format(java.util.Date())}.${BackupRepository.FILE_EXTENSION}"
}

internal fun navEntry(
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

    if (initial.contentKey is BrowserNavDestination.Tabs && target.contentKey is BrowserNavDestination.Browser) {
        return ContentTransform(
            initialContentExit = slideOut {
                IntOffset(x = 0, y = heightProvider(it.height))
            },
            targetContentEnter = EnterTransition.None,
        )
    }

    if (target.contentKey is BrowserNavDestination.Browser) {
        return ContentTransform(
            initialContentExit = ExitTransition.None,
            targetContentEnter = EnterTransition.None,
        )
    }

    return default
}

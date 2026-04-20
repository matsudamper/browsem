package net.matsudamper.browser

import androidx.activity.compose.BackHandler
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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreenViewModel
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.browser.BrowserScreen
import net.matsudamper.browser.ui.downloads.DownloadManagementScreen
import net.matsudamper.browser.ui.extensions.ExtensionsScreen
import net.matsudamper.browser.ui.history.HistoryScreen
import net.matsudamper.browser.ui.settings.SettingsScreen
import net.matsudamper.browser.ui.tabs.TabsScreen
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoRuntime

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<String>,
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
        BrowserAppContent(
            uiState = uiState,
            browserTabController = viewModel.browserTabController,
            browserSessionLifecycleController = viewModel.browserSessionLifecycleController,
            themeColorExtension = viewModel.themeColorExtension,
            mediaWebExtension = viewModel.mediaWebExtension,
            runtime = viewModel.runtime,
            setupComplete = viewModel.setupComplete,
            eventHandler = viewModel.eventHandler,
            registerExternalTab = viewModel::registerExternalTab,
            createTabWithHomepage = viewModel::createTabWithHomepage,
            newTabUrlFlow = newTabUrlFlow,
            openDownloadsFlow = openDownloadsFlow,
            onInstallExtensionRequest = onInstallExtensionRequest,
            onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
        )
    }
}

@Composable
private fun BrowserAppContent(
    uiState: BrowserAppUiState,
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: net.matsudamper.browser.media.MediaWebExtension,
    runtime: GeckoRuntime,
    setupComplete: kotlinx.coroutines.Deferred<Unit>,
    eventHandler: kotlinx.coroutines.channels.Channel<(BrowserViewModel.Event) -> Unit>,
    registerExternalTab: (String) -> Unit,
    createTabWithHomepage: suspend (String) -> BrowserTab,
    newTabUrlFlow: Flow<String>,
    openDownloadsFlow: Flow<Unit>,
    onInstallExtensionRequest: (String) -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
) {

    // Koin からリポジトリを取得（画面 ViewModel に直接渡す）
    val settingsRepository: SettingsRepository = koinInject()
    val historyRepository: HistoryRepository = koinInject()
    val webSuggestionRepository: WebSuggestionRepository = koinInject()
    val tabGroupRepository: TabGroupRepository = koinInject()

    val backStack = rememberNavBackStack(AppDestination.Setup)
    val navController = remember(backStack) { NavController(backStack = backStack) }
    // タブ復元完了シグナルはパラメータで受け取り済み

    // ナビゲーションとViewModelの両方にタブ選択を通知するヘルパー
    val selectTab: (String, AppDestination.Browser?) -> Unit = remember(navController, browserTabController) {
        { tabId, beforeTab ->
            navController.selectTab(tabId, beforeTab)
            browserTabController.selectTab(tabId)
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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

    // 通知タップ時にダウンロード管理画面を開く
    LaunchedEffect(openDownloadsFlow) {
        openDownloadsFlow.onEach {
            if (backStack.none { it is AppDestination.Downloads }) {
                backStack.add(AppDestination.Downloads)
            }
        }.launchIn(this)
    }

    // タブ復元が開始される。復元完了後に遷移先タブを確定する。
    LaunchedEffect(eventHandler) {
        eventHandler.receiveAsFlow().collect { event ->
            event(object : BrowserViewModel.Event {
                override fun onTabsRestored(tabId: String) {
                    when {
                        backStack.firstOrNull() is AppDestination.Setup -> {
                            // 通常起動: Setup プレースホルダーから復元済みタブへ遷移する
                            selectTab(tabId, null)
                        }
                        else -> {
                            // savedInstanceState 復元時: backstack がすでに Browser を指している。
                            // そのタブが DB に存在しない場合（外部タブがクリーンアップ済み等）のみ遷移する。
                            val currentBrowserTab = backStack.filterIsInstance<AppDestination.Browser>().lastOrNull()
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
        // タブ復元完了を待ってから外部URLを処理する（レースコンディション防止）
        setupComplete.await()
        newTabUrlFlow.collect { url ->
            // デフォルトグループが設定されている場合、createAndAppendTab より先に DB 行を作成して
            // グループを確定させる。こうすることで TabsScreenViewModel のウォッチャーが発火した際に
            // このタブはすでに assignedTabIds に含まれ、アクティブグループへの上書きを防ぐ。
            val tabId = UUID.randomUUID().toString()
            // デフォルトグループは外部アプリ（Intent）経由でURLを開いた場合にのみ適用する。
            // タブ一覧での新規追加・履歴・拡張機能など、アプリ内操作には使用しない。
            val defaultGroupId = tabGroupRepository.getDefaultGroupId()
            if (defaultGroupId != null) {
                tabGroupRepository.assignTabToGroup(tabId, defaultGroupId)
            }
            val newTab = browserTabController.createAndAppendTab(tabId = tabId, initialUrl = url)
            // selectTab より前に呼ぶことで、外部タブ開封前の selectedTabId を記録できる
            registerExternalTab(newTab.tabId)
            selectTab(newTab.tabId, null)
        }
    }

    BackHandler(enabled = navController.isLastBackHandled) {
        navController.back()
    }

    BrowserTheme(themeMode = uiState.themeMode) {
        NavDisplay(
            backStack = backStack,
            onBack = { navController.back() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
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
                        // タブ復元は BrowserViewModel.init で開始し、ナビゲーションは
                        // BrowserAppContent の eventHandler LaunchedEffect が行う
                    }

                    is AppDestination.Browser -> navEntry(key) {
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
                        // タブが閉じられた際にコルーチンスコープをキャンセルしてリークを防ぐ
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
                                    tabCount = tabCount,
                                    onOpenTabs = {},
                                    toolbarMenu = {},
                                    gestureState = null,
                                    updateVisibleMenu = {},
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
                                    onOpenSettings = { backStack.add(AppDestination.Settings) },
                                    onOpenTabs = { backStack.add(AppDestination.Tabs) },
                                    browserSessionLifecycleController = browserSessionLifecycleController,
                                    onOpenNewSessionRequest = { uri ->
                                        val newTab = browserTabController.createTabForNewSession(
                                            initialUrl = uri,
                                            openerTabId = key.tabId,
                                        )
                                        // target="_blank" で開いたタブはオープナーと同じグループに割り当てる。
                                        // デフォルトグループは外部 Intent 経由の場合にのみ適用するため、ここでは使用しない。
                                        scope.launch {
                                            assignTabToOpenerGroup(newTab.tabId, key.tabId)
                                        }
                                        selectTab(newTab.tabId, key)
                                        newTab.session
                                    },
                                    onOpenNewTabRequest = { uri ->
                                        scope.launch {
                                            val tabId = UUID.randomUUID().toString()
                                            assignTabToOpenerGroup(tabId, key.tabId)
                                            val newTab = browserTabController.createAndAppendTab(
                                                tabId = tabId,
                                                initialUrl = uri,
                                                openerTabId = key.tabId,
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
                                    onUrlInputChange = browserScreenUiState.callbacks::onUrlInputChanged,
                                    onToolbarHorizontalDrag = onToolbarHorizontalDrag,
                                    onToolbarDragEnd = onToolbarDragEnd,
                                )
                            },
                        )
                    }

                    AppDestination.Settings -> navEntry(key) {
                        val settingsViewModel = remember(settingsRepository) {
                            SettingsScreenViewModel(settingsRepository)
                        }
                        val settingsUiState by settingsViewModel.uiState.collectAsState()
                        settingsUiState?.let { uiState ->
                            SettingsScreen(
                                uiState = uiState,
                                onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                                onOpenHistory = { backStack.add(AppDestination.History) },
                                onOpenDownloads = { backStack.add(AppDestination.Downloads) },
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                    }

                    AppDestination.History -> navEntry(key) {
                        val historyViewModel = remember {
                            HistoryScreenViewModel(historyRepository)
                        }
                        val historyUiState by historyViewModel.uiState.collectAsState()
                        LaunchedEffect(historyViewModel) {
                            historyViewModel.eventHandler.receiveAsFlow().collect {
                                it(object : HistoryScreenViewModel.Event {
                                    override fun navigateToUrl(url: String) {
                                        scope.launch {
                                            val tabId = UUID.randomUUID().toString()
                                            withContext(Dispatchers.Main) {
                                                browserTabController.createAndAppendTab(
                                                    tabId = tabId,
                                                    initialUrl = url,
                                                )
                                                selectTab(tabId, null)
                                            }
                                        }
                                    }
                                })
                            }
                        }
                        HistoryScreen(
                            uiState = historyUiState,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Extensions -> navEntry(key) {
                        val extensionsViewModel = remember(runtime) {
                            ExtensionsScreenViewModel(runtime = runtime)
                        }
                        val extensionsUiState by extensionsViewModel.uiState.collectAsState()
                        LaunchedEffect(extensionsViewModel) {
                            extensionsViewModel.eventHandler.receiveAsFlow().collect {
                                it(object : ExtensionsScreenViewModel.Event {
                                    override fun navigateToExtensionSettings(url: String) {
                                        scope.launch {
                                            val tabId = UUID.randomUUID().toString()
                                            withContext(Dispatchers.Main) {
                                                browserTabController.createAndAppendTab(
                                                    tabId = tabId,
                                                    initialUrl = url,
                                                )
                                                selectTab(tabId, null)
                                            }
                                        }
                                    }
                                })
                            }
                        }
                        ExtensionsScreen(
                            uiState = extensionsUiState,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Downloads -> navEntry(key) {
                        val downloadsViewModel = remember {
                            DownloadManagementScreenViewModel(context.applicationContext as android.app.Application)
                        }
                        val downloadsUiState by downloadsViewModel.uiState.collectAsState()
                        DownloadManagementScreen(
                            uiState = downloadsUiState,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Tabs -> navEntry(key) {
                        val tabsViewModel = composeViewModel(initializer = {
                            TabsScreenViewModel(
                                tabStore = browserTabController,
                                tabGroupRepository = tabGroupRepository,
                            )
                        })
                        val tabsUiState by tabsViewModel.uiState.collectAsState()
                        DisposableEffect(Unit) {
                            onDispose { navController.disposeTabs() }
                        }
                        LaunchedEffect(tabsViewModel) {
                            tabsViewModel.eventHandler.receiveAsFlow().collect {
                                it(object : TabsScreenViewModel.Event {
                                    override fun closeTab(tabId: String) {
                                        val wasCurrentBrowserTab = navController.getSelectedTab() == tabId
                                        val nextSelectedTabId = browserTabController.closeTab(tabId)
                                        if (nextSelectedTabId == null) {
                                            scope.launch {
                                                val newTab = createTabWithHomepage(
                                                    UUID.randomUUID().toString(),
                                                )
                                                selectTab(newTab.tabId, null)
                                            }
                                        } else if (wasCurrentBrowserTab) {
                                            // タブ一覧は開いたまま、戻り先の Browser だけ最新の選択タブへ同期する
                                            navController.replaceCurrentBrowserTab(nextSelectedTabId)
                                        }
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
                                // タブ一覧画面からの新規タブ追加。現在表示中のグループに割り当てる。
                                // タブ作成前に割り当てを確定させ、selectTab 後の ViewModel 破棄で
                                // 未割当モニターがキャンセルされても正しいグループが復元されるようにする。
                                scope.launch {
                                    val tabId = UUID.randomUUID().toString()
                                    if (currentGroupId != null) {
                                        tabGroupRepository.assignTabToGroup(tabId, currentGroupId)
                                    }
                                    val newTab = createTabWithHomepage(tabId)
                                    selectTab(newTab.tabId, null)
                                }
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

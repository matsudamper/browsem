package net.matsudamper.browser

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.extractSiteHost
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.backup.BackupProgressViewModel
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreenViewModel
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.browser.BrowserScreen
import net.matsudamper.browser.ui.downloads.DownloadManagementScreen
import net.matsudamper.browser.ui.extensions.ExtensionsScreen
import net.matsudamper.browser.ui.history.HistoryScreen
import net.matsudamper.browser.ui.settings.BackupProgressScreen
import net.matsudamper.browser.ui.settings.BackupProgressUiState
import net.matsudamper.browser.ui.settings.SettingsScreen
import net.matsudamper.browser.ui.settings.SiteSettingsScreen
import net.matsudamper.browser.ui.tabs.TabsScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.WorkManager
import net.matsudamper.browser.data.BackupRepository
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoRuntime

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<NewTabRequest>,
    openDownloadsFlow: Flow<String?>,
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
            viewModel = viewModel,
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
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<NewTabRequest>,
    openDownloadsFlow: Flow<String?>,
    onInstallExtensionRequest: (String) -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
) {
    val browserTabController = viewModel.browserTabController
    val browserSessionLifecycleController = viewModel.browserSessionLifecycleController
    val themeColorExtension = viewModel.themeColorExtension
    val mediaWebExtension = viewModel.mediaWebExtension
    val runtime = viewModel.runtime

    // Koin からリポジトリを取得（画面 ViewModel に直接渡す）
    val settingsRepository: SettingsRepository = koinInject()
    val historyRepository: HistoryRepository = koinInject()
    val webSuggestionRepository: WebSuggestionRepository = koinInject()
    val tabGroupRepository: TabGroupRepository = koinInject()

    val backStack = rememberNavBackStack(AppDestination.Setup)
    val navController = remember(backStack) { NavController(backStack = backStack) }
    // タブ復元完了シグナルは ViewModel で保持（構成変更後も有効）
    val setupComplete = viewModel.setupComplete

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
    var pendingHighlightWorkerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openDownloadsFlow) {
        openDownloadsFlow.onEach { workerId ->
            pendingHighlightWorkerId = workerId
            val existingIndex = backStack.indexOfLast { it is AppDestination.Downloads }
            if (existingIndex < 0) {
                backStack.add(AppDestination.Downloads)
            } else if (existingIndex < backStack.lastIndex) {
                // ダウンロード画面が他の画面の下に埋もれている場合、上の画面を取り除いて前面に出す
                val removeCount = backStack.lastIndex - existingIndex
                repeat(removeCount) { backStack.removeLastOrNull() }
            }
        }.launchIn(this)
    }

    // ViewModel.init でタブ復元が開始される。復元完了後に遷移先タブを確定する。
    LaunchedEffect(viewModel) {
        viewModel.eventHandler.receiveAsFlow().collect { event ->
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
        newTabUrlFlow.collect { request ->
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
            // カスタムタブからの引き継ぎがある場合は SessionState を復元して履歴ごと開く
            val newTab = browserTabController.createAndAppendTab(
                tabId = tabId,
                initialUrl = request.url,
                restoredSessionState = request.sessionState,
                initialReferrerUrl = request.referrerUrl,
                insertAfterSelectedTab = false,
            )
            // selectTab より前に呼ぶことで、外部タブ開封前の selectedTabId を記録できる
            viewModel.registerExternalTab(newTab.tabId)
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
                    val targetBrowser = target.contentKey as AppDestination.Browser
                    // 遷移元のタブから新しいタブを開いた場合のみ右にスライド。
                    // beforeTab の存在だけで判定すると、連鎖的にタブを開いた後の
                    // back() 時にも beforeTab が残っているためスライドが誤って再生される。
                    if (targetBrowser.beforeTab == initial.contentKey) {
                        return@NavDisplay ContentTransform(
                            targetContentEnter = slideIn { IntOffset(it.width, 0) },
                            initialContentExit = slideOut { IntOffset(-it.width / 3, 0) },
                        )
                    }
                    // ジェスチャーでのタブ切替は BrowserScreen 側でアニメーションを処理するため、
                    // NavDisplay 側では即座に切り替える
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
                                    toolbarColor = tab.themeColor?.let { Color(it) },
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
                                    onOpenSettings = { backStack.add(AppDestination.Settings) },
                                    onOpenDownloads = {
                                        if (backStack.none { it is AppDestination.Downloads }) {
                                            backStack.add(AppDestination.Downloads)
                                        }
                                    },
                                    onOpenSiteSettings = { currentUrl ->
                                        val host = extractSiteHost(currentUrl)
                                        if (host != null) {
                                            backStack.add(
                                                AppDestination.SiteSettings(
                                                    host = host,
                                                    tabId = key.tabId,
                                                ),
                                            )
                                        }
                                    },
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
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            // 地図アプリがインストールされていない端末では何もしない
                                        }
                                    }

                                    override fun onNavigateToBackupProgress(isImport: Boolean) {
                                        backStack.add(AppDestination.BackupProgress(isImport))
                                    }

                                    override fun onRestartProcess() {
                                        android.os.Handler(android.os.Looper.getMainLooper())
                                            .postDelayed({
                                                android.os.Process.killProcess(
                                                    android.os.Process.myPid(),
                                                )
                                            }, 300)
                                    }
                                })
                            }
                        }
                        settingsUiState?.let { uiState ->
                            SettingsScreen(
                                uiState = uiState,
                                onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                                onOpenHistory = { backStack.add(AppDestination.History) },
                                onBack = { backStack.removeLastOrNull() },
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
                                // 開いた元のタブの現在の接続から TLS 証明書情報を取得する
                                securityInfo = key.tabId
                                    ?.let { browserTabController.findTab(it) }
                                    ?.securityInfo,
                            )
                        })
                        // 「実際の位置情報」選択時に OS の位置情報権限を要求する
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
                            onBack = { backStack.removeLastOrNull() },
                        )
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
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Downloads -> navEntry(key) {
                        val downloadsViewModel = remember {
                            DownloadManagementScreenViewModel(context.applicationContext as android.app.Application)
                        }
                        val downloadsUiState by downloadsViewModel.uiState.collectAsState()
                        var highlightItemId by remember { mutableStateOf<UUID?>(null) }
                        LaunchedEffect(pendingHighlightWorkerId) {
                            val workerId = pendingHighlightWorkerId ?: return@LaunchedEffect
                            val id = runCatching { UUID.fromString(workerId) }.getOrNull() ?: return@LaunchedEffect
                            pendingHighlightWorkerId = null
                            downloadsViewModel.requestHighlight(id)
                        }
                        LaunchedEffect(downloadsViewModel) {
                            downloadsViewModel.eventHandler.receiveAsFlow().collect {
                                it(object : DownloadManagementScreenViewModel.Event {
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

                                    override fun highlightItem(id: UUID) {
                                        highlightItemId = id
                                    }
                                })
                            }
                        }
                        DownloadManagementScreen(
                            uiState = downloadsUiState,
                            onBack = { backStack.removeLastOrNull() },
                            highlightItemId = highlightItemId,
                            onHighlightComplete = { highlightItemId = null },
                        )
                    }

                    AppDestination.Tabs -> navEntry(key) {
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
                                        // タブの閉鎖自体は ViewModel が TabStore へ直接行っているため、
                                        // ここではナビゲーション側の後処理のみを行う
                                        val wasCurrentBrowserTab = navController.getSelectedTab() == closedTabId
                                        if (nextSelectedTabId == null) {
                                            scope.launch {
                                                val newTab = viewModel.createTabWithHomepage(
                                                    tabId = UUID.randomUUID().toString(),
                                                )
                                                selectTab(newTab.tabId, null)
                                            }
                                        } else if (wasCurrentBrowserTab) {
                                            // タブ一覧は開いたまま、戻り先の Browser だけ最新の選択タブへ同期する
                                            navController.replaceCurrentBrowserTab(nextSelectedTabId)
                                        }
                                    }

                                    override fun selectTab(tabId: String) {
                                        // タブ閉鎖の保留・取り消しに伴う選択切替。
                                        // タブ一覧は開いたまま、戻り先の Browser だけ切り替える
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
                                // タブ一覧画面からの新規タブ追加。現在表示中のグループに割り当てる。
                                // タブ作成前に割り当てを確定させ、selectTab 後の ViewModel 破棄で
                                // 未割当モニターがキャンセルされても正しいグループが復元されるようにする。
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

                    is AppDestination.BackupProgress -> navEntry(key) {
                        val backupRepository: BackupRepository = koinInject()
                        val backupViewModel = composeViewModel(initializer = {
                            BackupProgressViewModel(key.isImport, backupRepository)
                        })
                        val backupUiState by backupViewModel.uiState.collectAsState()

                        // 一時停止したタブIDを記録する（後から追加されるタブも追跡するため）
                        val pausedTabIds = remember { mutableSetOf<String>() }

                        // タブ一覧の変化を監視し、新たに追加されたタブも含めて一時停止する
                        LaunchedEffect(browserTabController, browserSessionLifecycleController) {
                            browserTabController.tabStoreState.collect {
                                browserTabController.tabs.forEach { tab ->
                                    if (pausedTabIds.add(tab.tabId)) {
                                        browserSessionLifecycleController.pauseSession(tab)
                                    }
                                }
                            }
                        }

                        // 画面離脱時に一時停止したすべてのタブを再開する
                        DisposableEffect(browserTabController, browserSessionLifecycleController) {
                            onDispose {
                                browserTabController.tabs
                                    .filter { it.tabId in pausedTabIds }
                                    .forEach { tab ->
                                        browserSessionLifecycleController.resumeSession(tab)
                                    }
                            }
                        }

                        // InProgress に移行した時点で1度だけダウンロードをキャンセルする。
                        // phase 自体を key にすると進捗メッセージ更新ごとに再実行されるため、
                        // InProgress であるかどうかの boolean を key にして遷移時のみ走らせる。
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
                                backStack.removeLastOrNull()
                            }
                        }
                        val importLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument(),
                        ) { uri ->
                            if (uri != null) {
                                backupViewModel.startWithUri(uri)
                            } else {
                                backStack.removeLastOrNull()
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
                                        // DataStore と Room のキャッシュを完全に捨てて
                                        // 新しいファイルを読み込ませるため、プロセスごと終了する
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                    }

                                    override fun onNavigateBack() {
                                        backStack.removeLastOrNull()
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
}

/**
 * 日時付きのバックアップファイル名を生成する。
 * SAF のファイル作成ピッカーに初期ファイル名として渡す。
 */
private fun buildBackupFileName(): String {
    val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    return "browsem-backup-${formatter.format(java.util.Date())}.${BackupRepository.FILE_EXTENSION}"
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

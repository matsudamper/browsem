package net.matsudamper.browser

import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
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
import net.matsudamper.browser.screen.browser.BrowserScreen
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreen
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreen
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.notificationpermissions.NotificationPermissionsScreen
import net.matsudamper.browser.screen.notificationpermissions.NotificationPermissionsScreenViewModel
import net.matsudamper.browser.screen.downloads.DownloadManagementScreen
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreen
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreen
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoResult

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<String>,
    openDownloadsFlow: Flow<Unit>,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    onRequestDownloadNotificationPermission: () -> Unit,
) {
    val currentSettings by viewModel.settingsUiState.collectAsState()
    val settingsUiState = currentSettings ?: return

    val homepageUrl = settingsUiState.homepageUrl
    val searchTemplate = settingsUiState.searchTemplate
    val browserTabController = viewModel.browserTabController
    val browserSessionLifecycleController = viewModel.browserSessionLifecycleController
    val themeColorExtension = viewModel.themeColorExtension
    val mediaWebExtension = viewModel.mediaWebExtension

    // Koin からリポジトリを取得（画面 ViewModel に直接渡す）
    val settingsRepository: SettingsRepository = koinInject()
    val historyRepository: HistoryRepository = koinInject()
    val webSuggestionRepository: WebSuggestionRepository = koinInject()
    val tabGroupRepository: TabGroupRepository = koinInject()

    LaunchedEffect(settingsUiState.enableThirdPartyCa) {
        viewModel.applyRuntimeSettings()
    }

    val backStack = rememberNavBackStack(AppDestination.Setup)
    val navController = remember(backStack) { NavController(backStack = backStack) }
    // タブ復元完了シグナルは ViewModel で保持（構成変更後も有効）
    val setupComplete = viewModel.setupComplete

    // ナビゲーションとViewModelの両方にタブ選択を通知するヘルパー
    val selectTab: (String, AppDestination.Browser?) -> Unit = remember(navController, viewModel) {
        { tabId, beforeTab ->
            navController.selectTab(tabId, beforeTab)
            viewModel.selectTab(tabId)
        }
    }

    // 外部 Intent から開いたタブの ID を追跡する
    val externalTabIds = remember { mutableStateSetOf<String>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 通知タップ時にダウンロード管理画面を開く
    LaunchedEffect(openDownloadsFlow) {
        openDownloadsFlow.onEach {
            if (backStack.none { it is AppDestination.Downloads }) {
                backStack.add(AppDestination.Downloads)
            }
        }.launchIn(this)
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
            // 外部から開いたタブとして記録する
            externalTabIds.add(newTab.tabId)
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

    // 外部 URL で開いたタブをバックで閉じる処理。
    // タブ移動・ホーム遷移等の操作なしにバックされた場合（isLastBackHandled == false）にのみ発火し、
    // タブを閉じて即座に保存してからアプリを終了する。
    val currentExternalTabId = run {
        val currentTabId = backStack.filterIsInstance<AppDestination.Browser>().lastOrNull()?.tabId
        currentTabId?.takeIf { it in externalTabIds }
    }
    BackHandler(enabled = !navController.isLastBackHandled && currentExternalTabId != null) {
        val tabId = currentExternalTabId ?: return@BackHandler
        scope.launch {
            viewModel.closeTabAndSaveImmediately(tabId, homepageUrl)
            (context as ComponentActivity).finish()
        }
    }

    BrowserTheme(themeMode = settingsUiState.themeMode) {
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
                        LaunchedEffect(Unit) {
                            val tabId = viewModel.restoreTabs()
                            selectTab(tabId, null)
                            // setupComplete は restoreTabs() 内で complete 済み
                        }
                    }

                    is AppDestination.Browser -> navEntry(key) {
                        val browserTabsFlow = remember(browserTabController) {
                            browserTabController.tabStoreState
                                .map { browserTabController.tabs.toList() }
                                .distinctUntilChanged()
                        }
                        val browserScreenViewModel = remember(tabGroupRepository, browserTabsFlow) {
                            BrowserScreenViewModel(
                                historyRepository = historyRepository,
                                settingsRepository = settingsRepository,
                                webSuggestionRepository = webSuggestionRepository,
                                tabGroupRepository = tabGroupRepository,
                                browserTabsFlow = browserTabsFlow,
                            )
                        }
                        // タブが閉じられた際にコルーチンスコープをキャンセルしてリークを防ぐ
                        DisposableEffect(key.tabId) {
                            onDispose { browserScreenViewModel.close() }
                        }
                        BrowserScreen(
                            key = key,
                            homepageUrl = homepageUrl,
                            searchTemplate = searchTemplate,
                            backStack = backStack,
                            browserTabController = browserTabController,
                            browserSessionLifecycleController = browserSessionLifecycleController,
                            viewModel = browserScreenViewModel,
                            navController = navController,
                            translationProvider = settingsUiState.translationProvider,
                            themeColorExtension = themeColorExtension,
                            mediaWebExtension = mediaWebExtension,
                            onInstallExtensionRequest = onInstallExtensionRequest,
                            handleNotificationPermission = handleNotificationPermission,
                            onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
                            onSelectTab = { tabId, beforeTab ->
                                selectTab(tabId, beforeTab)
                            },
                            onNewSessionTabCreated = { newTabId, openerTabId ->
                                // target="_blank" で開いたタブはオープナーと同じグループに割り当てる。
                                // デフォルトグループは外部 Intent 経由の場合にのみ適用するため、ここでは使用しない。
                                scope.launch {
                                    val openerGroupId = tabGroupRepository.observeTabGroupAssignments()
                                        .first()
                                        .find { it.tabId == openerTabId }
                                        ?.groupId
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { TabGroupId(it) }
                                    if (openerGroupId != null) {
                                        tabGroupRepository.assignTabToGroup(newTabId, openerGroupId)
                                    }
                                }
                            },
                        )
                    }

                    AppDestination.Settings -> navEntry(key) {
                        val settingsViewModel = remember(viewModel) {
                            SettingsScreenViewModel(settingsRepository, viewModel.settingsUiState)
                        }
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenExtensions = { backStack.add(AppDestination.Extensions) },
                            onOpenNotificationPermissions = {
                                backStack.add(AppDestination.NotificationPermissions)
                            },
                            onOpenHistory = { backStack.add(AppDestination.History) },
                            onOpenDownloads = { backStack.add(AppDestination.Downloads) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.History -> navEntry(key) {
                        val historyViewModel = remember {
                            HistoryScreenViewModel(historyRepository)
                        }
                        HistoryScreen(
                            viewModel = historyViewModel,
                            onNavigateToUrl = { url ->
                                scope.launch {
                                    val tabId = UUID.randomUUID().toString()
                                    withContext(Dispatchers.Main) {
                                        browserTabController.createAndAppendTab(
                                            tabId = tabId,
                                            initialUrl = url,
                                        )
                                        navController.selectTab(tabId)
                                    }
                                }
                            },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Extensions -> navEntry(key) {
                        val extensionsViewModel = remember(viewModel) {
                            ExtensionsScreenViewModel(runtime = viewModel.runtime)
                        }
                        ExtensionsScreen(
                            viewModel = extensionsViewModel,
                            onBack = { backStack.removeLastOrNull() },
                            onOpenExtensionSettings = { optionsPageUrl ->
                                scope.launch {
                                    val tabId = UUID.randomUUID().toString()
                                    withContext(Dispatchers.Main) {
                                        browserTabController.createAndAppendTab(
                                            tabId = tabId,
                                            initialUrl = optionsPageUrl,
                                        )
                                        selectTab(tabId, null)
                                    }
                                }
                            },
                        )
                    }

                    AppDestination.NotificationPermissions -> navEntry(key) {
                        val notificationPermissionsViewModel = remember(viewModel) {
                            NotificationPermissionsScreenViewModel(settingsRepository, viewModel.settingsUiState)
                        }
                        NotificationPermissionsScreen(
                            viewModel = notificationPermissionsViewModel,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Downloads -> navEntry(key) {
                        val downloadsViewModel = remember {
                            DownloadManagementScreenViewModel(context.applicationContext as android.app.Application)
                        }
                        DownloadManagementScreen(
                            viewModel = downloadsViewModel,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    AppDestination.Tabs -> navEntry(key) {
                        DisposableEffect(Unit) {
                            onDispose { navController.disposeTabs() }
                        }
                        TabsScreen(
                            tabStore = browserTabController,
                            tabGroupRepository = tabGroupRepository,
                            selectedTabId = browserTabController.selectedTabId,
                            onSelectTab = { tabId ->
                                selectTab(tabId, null)
                            },
                            onCloseTab = { tabId ->
                                val nextSelectedTabId = browserTabController.closeTab(tabId)
                                if (nextSelectedTabId == null) {
                                    scope.launch {
                                        val newTabId = UUID.randomUUID().toString()
                                        withContext(Dispatchers.Main) {
                                            browserTabController.createAndAppendTab(
                                                tabId = newTabId,
                                                initialUrl = homepageUrl,
                                            )
                                            selectTab(newTabId, null)
                                        }
                                    }
                                }
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
                                    // GeckoSession の生成は UI スレッドで行う必要がある
                                    withContext(Dispatchers.Main) {
                                        val newTab = browserTabController.createAndAppendTab(
                                            tabId = tabId,
                                            initialUrl = homepageUrl,
                                        )
                                        selectTab(newTab.tabId, null)
                                    }
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

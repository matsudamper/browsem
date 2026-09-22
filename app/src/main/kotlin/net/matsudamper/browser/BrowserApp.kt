package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.extractSiteHost
import net.matsudamper.browser.data.forminput.parseFormInputPageKey
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.navigation.AddressEditNavContent
import net.matsudamper.browser.navigation.AddressesNavContent
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.BackupProgressNavContent
import net.matsudamper.browser.navigation.BrowserNavDestination
import net.matsudamper.browser.navigation.CrashLogDetailNavContent
import net.matsudamper.browser.navigation.CrashLogsNavContent
import net.matsudamper.browser.navigation.DownloadsNavContent
import net.matsudamper.browser.navigation.ExtensionSettingsNavContent
import net.matsudamper.browser.navigation.ExtensionsNavContent
import net.matsudamper.browser.navigation.HistoryNavContent
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.navigation.PendingDownloadsOpenRequest
import net.matsudamper.browser.navigation.SettingsNavContent
import net.matsudamper.browser.navigation.SiteFormInputFieldNavContent
import net.matsudamper.browser.navigation.SiteFormInputPathNavContent
import net.matsudamper.browser.navigation.SiteFormInputPathsNavContent
import net.matsudamper.browser.navigation.SiteSettingsListNavContent
import net.matsudamper.browser.navigation.SiteSettingsNavContent
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreenViewModel
import net.matsudamper.browser.translate.PageTranslationWebExtension
import net.matsudamper.browser.ui.browser.BrowserContentLoadingIndicator
import net.matsudamper.browser.ui.browser.BrowserScreen
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.tabs.TabsScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.mozilla.geckoview.GeckoRuntime

@Composable
internal fun BrowserApp(
    viewModel: BrowserViewModel,
    newTabUrlFlow: Flow<NewTabRequest>,
    openDownloadsFlow: Flow<OpenDownloadsRequest>,
    onOpenDownloadsRequestConsumed: (String) -> Unit,
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
            // MainBrowserContent がコンポジションに戻ってから消費される。
            val selectTabRequester = remember { SelectTabRequester() }
            val tabGroupRepository: TabGroupRepository = koinInject()
            val pageTranslationWebExtension: PageTranslationWebExtension = koinInject()
            BrowserAppShell(
                browserTabController = viewModel.browserTabController,
                browserSessionLifecycleController = viewModel.browserSessionLifecycleController,
                runtime = viewModel.runtime,
                openDownloadsFlow = openDownloadsFlow,
                onOpenDownloadsRequestConsumed = onOpenDownloadsRequestConsumed,
                newTabUrlFlow = newTabUrlFlow,
                onExternalTabRequest = { request ->
                    val requestedSession = request.handedOffSession
                    // 載せるまでに画面が終わると、このセッションはどの Controller も破棄を通知しない
                    var handedOffSessionAttached = false
                    val newTab = try {
                        viewModel.setupComplete.await()
                        val tabId = UUID.randomUUID().toString()
                        val defaultGroupId = tabGroupRepository.getDefaultGroupId()
                        if (defaultGroupId != null) {
                            tabGroupRepository.assignTabToGroup(tabId, defaultGroupId)
                        }
                        // 要求から実際に載せるまでの間にコンテンツプロセスが停止していることがある。
                        // 閉じたセッションを載せると SessionState 経由の復元へ落ちられない。
                        val handedOffSession = requestedSession?.takeIf { it.isOpen }
                        if (handedOffSession != null) {
                            // カスタムタブから引き渡されたセッションは開いたまま載せる。open→restoreState で
                            // 復元すると読み込みが走り、ワンタイムトークンや POST 結果のページが壊れる。
                            viewModel.browserTabController.createAndAppendTabWithSession(
                                session = handedOffSession,
                                tabId = tabId,
                                initialUrl = request.url,
                            ).also { tab ->
                                handedOffSessionAttached = true
                                // 載せた直後に強制終了されてもページを復元できるよう、
                                // 引き渡し先の delegate 経由で SessionState を保存し直す。
                                tab.session.flushSessionState()
                            }
                        } else {
                            viewModel.browserTabController.createAndAppendTab(
                                tabId = tabId,
                                initialUrl = request.url,
                                restoredSessionState = request.sessionState,
                                initialReferrerUrl = request.referrerUrl,
                                insertAfterSelectedTab = false,
                            )
                        }
                    } finally {
                        if (requestedSession != null && !handedOffSessionAttached) {
                            pageTranslationWebExtension.unregisterSession(requestedSession)
                        }
                    }
                    viewModel.registerExternalTab(newTab.tabId, request.url)
                    selectTabRequester.request(newTab.tabId)
                },
                onNavigateToUrl = { url ->
                    val tabId = UUID.randomUUID().toString()
                    val newTab = viewModel.browserTabController.createAndAppendTab(
                        tabId = tabId,
                        initialUrl = url,
                    )
                    selectTabRequester.request(newTab.tabId)
                },
            ) { outerNavActions ->
                MainBrowserContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onInstallExtensionRequest = onInstallExtensionRequest,
                    onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
                    outerNavActions = outerNavActions,
                    selectTabRequester = selectTabRequester,
                )
            }
        }
    }
}

/**
 * 本体ブラウザ / WebApp / CustomTab の全モードで共有する外側シェル。
 * 全画面系の navEntry (Settings, SiteSettings, …) をここに集約し、
 * [rootContent] がモード別の内容を描画する。
 *
 * @param onNavigateToUrl 履歴やダウンロードからURLを開く際のコールバック。
 *   呼び出し側がタブ作成と内側ナビの更新を行う。
 *   タブ UI を持たない WebApp / CustomTab では null を渡す。この場合 URL タップは
 *   何もしない（画面も閉じない）。
 * @param onExternalTabRequest 外部 Intent から新規タブを開く際のコールバック。
 *   呼び出し側がタブ作成と内側ナビの更新を行う。受信時に外側スタックは Root まで戻す。
 */
@Composable
internal fun BrowserAppShell(
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    runtime: GeckoRuntime,
    openDownloadsFlow: Flow<OpenDownloadsRequest>? = null,
    onOpenDownloadsRequestConsumed: ((String) -> Unit)? = null,
    newTabUrlFlow: Flow<NewTabRequest>? = null,
    onExternalTabRequest: (suspend (NewTabRequest) -> Unit)? = null,
    onNavigateToUrl: (suspend (url: String) -> Unit)? = null,
    rootContent: @Composable (outerNavActions: OuterNavActions) -> Unit,
) {
    val outerBackStack = rememberNavBackStack(AppDestination.Root)
    val outerNavActions = remember(outerBackStack) { OuterNavActions(outerBackStack) }
    val pendingDownloadsOpenRequest = PendingDownloadsOpenRequest(
        isRequested = rememberSaveable { mutableStateOf(false) },
        highlightWorkerId = rememberSaveable { mutableStateOf<String?>(null) },
        requestId = rememberSaveable { mutableStateOf<String?>(null) },
        consumeByWorkerIdEntries = rememberSaveable { mutableStateOf(listOf<Pair<String, String>>()) },
    )
    if (openDownloadsFlow != null) {
        LaunchedEffect(openDownloadsFlow) {
            openDownloadsFlow.onEach { request ->
                pendingDownloadsOpenRequest.highlightWorkerId.value = request.workerId
                pendingDownloadsOpenRequest.requestId.value = request.requestId
                pendingDownloadsOpenRequest.isRequested.value = true
                val existingIndex = outerBackStack.indexOfLast { it is AppDestination.Downloads }
                if (existingIndex < 0) {
                    outerBackStack.add(AppDestination.Downloads)
                } else if (existingIndex < outerBackStack.lastIndex) {
                    val removeCount = outerBackStack.lastIndex - existingIndex
                    repeat(removeCount) { outerBackStack.removeLastOrNull() }
                }
            }.launchIn(this)
        }
    }

    if (newTabUrlFlow != null && onExternalTabRequest != null) {
        LaunchedEffect(newTabUrlFlow, onExternalTabRequest) {
            newTabUrlFlow.collect { request ->
                outerNavActions.popToRoot()
                onExternalTabRequest(request)
            }
        }
    }

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
                    rootContent(outerNavActions)
                }

                AppDestination.Settings -> navEntry(key) {
                    SettingsNavContent(navActions = outerNavActions)
                }

                AppDestination.SiteSettingsList -> navEntry(key) {
                    SiteSettingsListNavContent(navActions = outerNavActions)
                }

                is AppDestination.SiteSettings -> navEntry(key) {
                    SiteSettingsNavContent(
                        key = key,
                        navActions = outerNavActions,
                        browserTabController = browserTabController,
                    )
                }

                is AppDestination.SiteFormInputPaths -> navEntry(key) {
                    SiteFormInputPathsNavContent(key = key, navActions = outerNavActions)
                }

                is AppDestination.SiteFormInputPath -> navEntry(key) {
                    SiteFormInputPathNavContent(key = key, navActions = outerNavActions)
                }

                is AppDestination.SiteFormInputField -> navEntry(key) {
                    SiteFormInputFieldNavContent(key = key, navActions = outerNavActions)
                }

                AppDestination.History -> navEntry(key) {
                    HistoryNavContent(
                        navActions = outerNavActions,
                        onNavigateToUrl = onNavigateToUrl,
                    )
                }

                AppDestination.Addresses -> navEntry(key) {
                    AddressesNavContent(navActions = outerNavActions)
                }

                AppDestination.CrashLogs -> navEntry(key) {
                    CrashLogsNavContent(navActions = outerNavActions)
                }

                is AppDestination.CrashLogDetail -> navEntry(key) {
                    CrashLogDetailNavContent(key = key, navActions = outerNavActions)
                }

                is AppDestination.AddressEdit -> navEntry(key) {
                    AddressEditNavContent(key = key, navActions = outerNavActions)
                }

                AppDestination.Extensions -> navEntry(key) {
                    ExtensionsNavContent(navActions = outerNavActions)
                }

                is AppDestination.ExtensionSettings -> navEntry(key) {
                    ExtensionSettingsNavContent(
                        key = key,
                        navActions = outerNavActions,
                        onNavigateToUrl = onNavigateToUrl,
                    )
                }

                AppDestination.Downloads -> navEntry(key) {
                    DownloadsNavContent(
                        navActions = outerNavActions,
                        openRequest = pendingDownloadsOpenRequest,
                        onOpenDownloadsRequestConsumed = onOpenDownloadsRequestConsumed,
                        onNavigateToUrl = onNavigateToUrl,
                    )
                }

                is AppDestination.BackupProgress -> navEntry(key) {
                    BackupProgressNavContent(
                        key = key,
                        navActions = outerNavActions,
                        browserTabController = browserTabController,
                        browserSessionLifecycleController = browserSessionLifecycleController,
                    )
                }

                else -> error("Unknown destination: $key")
            }
        },
    )
}

@Stable
internal class OuterNavActions(private val backStack: MutableList<NavKey>) {
    fun add(destination: AppDestination) {
        backStack.add(destination)
    }

    fun addIfAbsent(destination: AppDestination) {
        if (backStack.none { it == destination }) backStack.add(destination)
    }

    fun pop() {
        backStack.removeLastOrNull()
    }

    fun popToRoot() {
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    /**
     * 現在のページのホストでサイト設定を開く。ホストを取り出せない URL（about: など）では何もしない。
     */
    fun openSiteSettings(currentUrl: String, tabId: String?) {
        val pageKey = parseFormInputPageKey(currentUrl)
        val host = extractSiteHost(currentUrl) ?: pageKey?.host ?: return
        add(
            AppDestination.SiteSettings(
                host = host,
                scheme = pageKey?.scheme ?: "https",
                port = pageKey?.port ?: 443,
                tabId = tabId,
            ),
        )
    }
}

/**
 * 外側シェル（履歴・ダウンロード）から内側ナビへタブ選択を依頼するための中継。
 *
 * 外側スタックに History / Downloads が積まれている間、Root の navEntry である
 * [MainBrowserContent] はコンポジションから外れる。コールバック参照を保持すると
 * 破棄済みコンポジションの `NavBackStack` を変更してしまい、Root 復帰時に
 * rememberSaveable のスナップショットから復元されて変更が失われる。
 * そのため要求を Channel にバッファし、Root 復帰後に生きている backStack へ適用する。
 */
@Stable
internal class SelectTabRequester {
    private val channel = Channel<String>(Channel.BUFFERED)

    val requests: Flow<String> = channel.receiveAsFlow()

    fun request(tabId: String) {
        channel.trySend(tabId)
    }
}

@Composable
private fun MainBrowserContent(
    uiState: BrowserAppUiState,
    viewModel: BrowserViewModel,
    onInstallExtensionRequest: (String) -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
    outerNavActions: OuterNavActions,
    selectTabRequester: SelectTabRequester,
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
    // 履歴・ダウンロードから開かれた URL のタブ選択要求を、Root 復帰後に消費する
    LaunchedEffect(selectTabRequester, selectTab) {
        selectTabRequester.requests.collect { tabId ->
            selectTab(tabId, null)
        }
    }

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

    LaunchedEffect(viewModel) {
        // 構成変更後は onTabsRestored が既に消費済み。NavBackStack が Setup に戻ったり
        // 別タブに復元されたりしても、ViewModel 上の選択タブへ戻す。
        if (viewModel.setupComplete.isCompleted) {
            browserTabController.selectedTabId?.let { navController.syncToSelectedTab(it) }
        }
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

                override fun onExternalDownloadTabClosed(targetTabId: String?) {
                    if (targetTabId != null) {
                        selectTab(targetTabId, null)
                    }
                }
            })
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
                val targetBrowser = target.contentKey as BrowserNavDestination.Browser
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
                    val browserScreenViewModel = remember(key.tabId, tabGroupRepository, browserTabsFlow, viewModel) {
                        BrowserScreenViewModel(
                            historyRepository = historyRepository,
                            settingsRepository = settingsRepository,
                            webSuggestionRepository = webSuggestionRepository,
                            tabGroupRepository = tabGroupRepository,
                            browserTabsFlow = browserTabsFlow,
                            screenTabId = key.tabId,
                            externalTabIdsFlow = viewModel.externalTabIds,
                            externalTabInitialUrlsFlow = viewModel.externalTabInitialUrls,
                        )
                    }
                    DisposableEffect(browserScreenViewModel) {
                        onDispose { browserScreenViewModel.close() }
                    }
                    val browserScreenUiState by browserScreenViewModel.uiState.collectAsState()
                    LaunchedEffect(browserScreenViewModel) {
                        browserScreenViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : BrowserScreenViewModel.Event {
                                override fun selectTab(tabId: String) {
                                    selectTab(tabId, null)
                                }

                                override fun backToOpenerTab(tabId: String) {
                                    val openerTabId = browserTabController.findTab(tabId)?.openerTabId
                                    val fallbackTabId = browserTabController.closeTab(tabId)
                                    val targetTabId = openerTabId
                                        ?.takeIf { browserTabController.findTab(it) != null }
                                        ?: fallbackTabId
                                    if (targetTabId != null) {
                                        selectTab(targetTabId, null)
                                    }
                                }

                                override fun onExternalDownloadDialogResolved(tabId: String) {
                                    viewModel.onExternalDownloadDialogResolved(tabId)
                                }
                            })
                        }
                    }
                    val selectedTab = rememberSelectedBrowserTab(
                        tabId = key.tabId,
                        homepageUrl = uiState.homepageUrl,
                        browserTabController = browserTabController,
                    )
                    if (selectedTab == null) {
                        BrowserContentLoadingIndicator()
                    } else {
                        BrowserScreen(
                            tabId = key.tabId,
                            uiState = browserScreenUiState,
                            canGoBackInPage = selectedTab.canGoBack,
                            previewHeaderContent = { modifier, preview, tabCount ->
                                BrowserToolbar(
                                    modifier = modifier,
                                    toolbarColor = preview.themeColor?.let { Color(it) },
                                    isFocused = false,
                                    onLongClickUrl = {},
                                    tabCount = tabCount,
                                    onOpenTabs = {},
                                    toolbarMenu = { _ -> },
                                    gestureState = null,
                                    updateVisibleMenu = {},
                                    canGoForward = false,
                                    onForward = {},
                                    canGoBack = false,
                                    onBack = {},
                                    onRefresh = {},
                                    onSuperRefresh = {},
                                    isPageLoading = false,
                                    onStopLoading = {},
                                    showTabButton = true,
                                    onTranslatePage = {},
                                    onLongPressHistory = {},
                                    urlInputState = UrlInputState(
                                        value = preview.currentUrl,
                                        onValueChange = {},
                                        onSubmit = {},
                                        onFocusChanged = {},
                                        enableSuggest = false,
                                        scrollEnabled = false,
                                    ),
                                )
                            },
                            browserTabContent = { modifier, tabCount, onToolbarHorizontalDrag, onToolbarDragEnd ->
                                GeckoBrowserTab(
                                    modifier = modifier,
                                    browserTab = selectedTab,
                                    homepageUrl = uiState.homepageUrl,
                                    searchTemplate = uiState.searchTemplate,
                                    translationProvider = uiState.translationProvider,
                                    geminiNanoModelKey = uiState.geminiNanoModelKey,
                                    themeColorExtension = themeColorExtension,
                                    mediaWebExtension = mediaWebExtension,
                                    tabCount = tabCount,
                                    onInstallExtensionRequest = onInstallExtensionRequest,
                                    onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
                                    enableTabUi = true,
                                    showInstallExtensionItem = true,
                                    customTabMode = false,
                                    webAppMode = false,
                                    webAppPinnedHost = null,
                                    onWebAppCrossDomainNavigation = null,
                                    onCloseCustomTab = null,
                                    onOpenInBrowser = null,
                                    onOpenSettings = { outerNavActions.add(AppDestination.Settings) },
                                    onOpenDownloads = {
                                        outerNavActions.addIfAbsent(AppDestination.Downloads)
                                    },
                                    onOpenSiteSettings = { currentUrl ->
                                        outerNavActions.openSiteSettings(currentUrl, key.tabId)
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
                                        WindowOpenSessionPolicy.scheduleSelectAfterCallback(
                                            selectTab = { selectTab(newTab.tabId, key) },
                                            retainOpeners = {
                                                browserSessionLifecycleController.retainOpenersOfLivePopups(
                                                    tabs = browserTabController.tabs,
                                                    selectedTabId = browserTabController.selectedTabId,
                                                )
                                            },
                                        )
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
                                    externalDownloadDialogListener = browserScreenUiState.externalDownloadDialogListener,
                                    externalTabInitialUrl = browserScreenUiState.externalTabInitialUrl,
                                    onHistoryRecord = browserScreenUiState.callbacks::onHistoryRecord,
                                    onHistoryTitleUpdate = browserScreenUiState.callbacks::onHistoryTitleUpdate,
                                    urlBarSuggestions = browserScreenUiState.urlBarSuggestions,
                                    onUrlInputChanged = browserScreenUiState.callbacks::onUrlInputChanged,
                                    onReevaluateOpenerRetention = {
                                        WindowOpenSessionPolicy.postAfterFrame {
                                            browserSessionLifecycleController.retainOpenersOfLivePopups(
                                                tabs = browserTabController.tabs,
                                                selectedTabId = browserTabController.selectedTabId,
                                            )
                                        }
                                    },
                                    onToolbarHorizontalDrag = onToolbarHorizontalDrag,
                                    onToolbarDragEnd = onToolbarDragEnd,
                                )
                            },
                        )
                    }
                }

                BrowserNavDestination.Tabs -> navEntry(key) {
                    val tabsViewModel: TabsScreenViewModel = koinViewModel {
                        parametersOf(browserTabController)
                    }
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

                                override fun openTab(tabId: String) {
                                    selectTab(tabId, null)
                                }

                                override fun openNewTab(currentGroupId: TabGroupId?) {
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
                                }
                            })
                        }
                    }
                    TabsScreen(
                        uiState = tabsUiState,
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

internal const val GITHUB_RELEASES_URL = "https://github.com/matsudamper/browsem/releases"

internal fun buildBackupFileName(): String {
    val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    return "browsem-backup-${formatter.format(Date())}.${BackupRepository.FILE_EXTENSION}"
}

internal fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
    message: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

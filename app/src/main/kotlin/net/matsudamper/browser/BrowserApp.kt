package net.matsudamper.browser

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import net.matsudamper.browser.ui.settings.site.SiteSettingsScreen
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupId
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.extractSiteHost
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.navigation.AppDestination
import net.matsudamper.browser.navigation.BrowserNavDestination
import net.matsudamper.browser.navigation.NavController
import net.matsudamper.browser.screen.backup.BackupProgressViewModel
import net.matsudamper.browser.screen.browser.BrowserScreenViewModel
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.screen.addresses.AddressEditScreenViewModel
import net.matsudamper.browser.screen.addresses.AddressesScreenViewModel
import net.matsudamper.browser.screen.crashlog.CrashLogDetailScreenViewModel
import net.matsudamper.browser.screen.crashlog.CrashLogsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputFieldScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputPathScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputPathsScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenViewModel
import net.matsudamper.browser.data.forminput.FormInputOrigin
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.forminput.parseFormInputPageKey
import net.matsudamper.browser.screen.tab.TabsScreenViewModel
import net.matsudamper.browser.ui.browser.BrowserScreen
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.downloads.DownloadManagementScreen
import net.matsudamper.browser.ui.extensions.ExtensionsScreen
import net.matsudamper.browser.ui.history.HistoryScreen
import net.matsudamper.browser.ui.settings.crash.CrashLogDetailRoute
import net.matsudamper.browser.ui.settings.crash.CrashLogsRoute
import net.matsudamper.browser.ui.settings.address.AddressEditScreen
import net.matsudamper.browser.ui.settings.address.AddressesScreen
import net.matsudamper.browser.ui.settings.backup.BackupProgressScreen
import net.matsudamper.browser.ui.settings.backup.BackupProgressUiState
import net.matsudamper.browser.ui.settings.SettingsScreen
import net.matsudamper.browser.ui.settings.form.SiteFormInputFieldScreen
import net.matsudamper.browser.ui.settings.form.SiteFormInputPathScreen
import net.matsudamper.browser.ui.settings.form.SiteFormInputPathsScreen
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
        BrowserTheme(themeMode = uiState.themeMode) {
            // 履歴・ダウンロード画面から URL を開く際、内側ナビのタブ選択が必要。
            // MainBrowserContent がコンポジションに戻ってから消費される。
            val selectTabRequester = remember { SelectTabRequester() }
            BrowserAppShell(
                browserTabController = viewModel.browserTabController,
                browserSessionLifecycleController = viewModel.browserSessionLifecycleController,
                runtime = viewModel.runtime,
                openDownloadsFlow = openDownloadsFlow,
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
                    newTabUrlFlow = newTabUrlFlow,
                    onInstallExtensionRequest = onInstallExtensionRequest,
                    onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
                    outerNavActions = outerNavActions,
                    selectTabRequester = selectTabRequester,
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

/**
 * @param onNavigateToUrl 履歴やダウンロードからURLを開く際のコールバック。
 *   呼び出し側がタブ作成と内側ナビの更新を行う。
 *   タブ UI を持たない WebApp / CustomTab では null を渡す。この場合 URL タップは
 *   何もしない（画面も閉じない）。
 */
@Composable
internal fun BrowserAppShell(
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    runtime: GeckoRuntime,
    openDownloadsFlow: Flow<String?>? = null,
    onNavigateToUrl: (suspend (url: String) -> Unit)? = null,
    rootContent: @Composable (outerNavActions: OuterNavActions) -> Unit,
) {
    val outerBackStack = rememberNavBackStack(AppDestination.Root)
    val outerNavActions = remember(outerBackStack) { OuterNavActions(outerBackStack) }
    val settingsRepository: SettingsRepository = koinInject()
    val historyRepository: HistoryRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 通知タップ時にダウンロード管理画面を開く
    var pendingHighlightWorkerId by remember { mutableStateOf<String?>(null) }
    if (openDownloadsFlow != null) {
        LaunchedEffect(openDownloadsFlow) {
            openDownloadsFlow.onEach { workerId ->
                pendingHighlightWorkerId = workerId
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
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val settingsViewModel = composeViewModel(initializer = {
                        SettingsScreenViewModel(settingsRepository)
                    })
                    val settingsUiState by settingsViewModel.uiState.collectAsState()
                    val requestDefaultBrowserLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) {
                        settingsViewModel.onDefaultBrowserStatusChecked(
                            DefaultBrowserChecker.isDefaultBrowser(context),
                        )
                    }
                    DisposableEffect(lifecycleOwner, settingsViewModel) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                settingsViewModel.refreshDefaultBrowserStatus()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
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

                                override fun onRestartProcess() {
                                    Handler(Looper.getMainLooper())
                                        .postDelayed({
                                            Process.killProcess(Process.myPid())
                                        }, 300)
                                }

                                override fun onOpenDefaultBrowserSettings() {
                                    val intent = DefaultBrowserChecker.createRequestDefaultBrowserIntent(context)
                                        ?: return
                                    requestDefaultBrowserLauncher.launch(intent)
                                }

                                override fun onCheckDefaultBrowserStatus() {
                                    settingsViewModel.onDefaultBrowserStatusChecked(
                                        DefaultBrowserChecker.isDefaultBrowser(context),
                                    )
                                }
                            })
                        }
                    }
                    settingsUiState?.let { uiState ->
                        SettingsScreen(
                            uiState = uiState,
                            onOpenExtensions = { outerBackStack.add(AppDestination.Extensions) },
                            onOpenHistory = { outerBackStack.add(AppDestination.History) },
                            onOpenAddresses = { outerBackStack.add(AppDestination.Addresses) },
                            onOpenCrashLogs = { outerBackStack.add(AppDestination.CrashLogs) },
                            onOpenReleases = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(GITHUB_RELEASES_URL),
                                        context,
                                        CustomTabActivity::class.java,
                                    ),
                                )
                            },
                            onBack = { outerBackStack.removeLastOrNull() },
                        )
                    }
                }

                is AppDestination.SiteSettings -> navEntry(key) {
                    val siteSettingsRepository: SiteSettingsRepository = koinInject()
                    val formInputRepository: FormInputRepository = koinInject()
                    val geckoRuntime: GeckoRuntime = koinInject()
                    val publicSuffixList: PublicSuffixList = koinInject()
                    val formInputOrigin = FormInputOrigin(
                        scheme = key.scheme,
                        host = key.host,
                        port = key.port,
                    )
                    val siteSettingsViewModel = composeViewModel(initializer = {
                        SiteSettingsScreenViewModel(
                            host = key.host,
                            formInputOrigin = formInputOrigin,
                            siteSettingsRepository = siteSettingsRepository,
                            formInputRepository = formInputRepository,
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

                                override fun navigateToSavedFormInputs() {
                                    outerBackStack.add(
                                        AppDestination.SiteFormInputPaths(
                                            scheme = formInputOrigin.scheme,
                                            host = formInputOrigin.host,
                                            port = formInputOrigin.port,
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

                is AppDestination.SiteFormInputPaths -> navEntry(key) {
                    val formInputRepository: FormInputRepository = koinInject()
                    val origin = FormInputOrigin(
                        scheme = key.scheme,
                        host = key.host,
                        port = key.port,
                    )
                    val pathsViewModel = composeViewModel(initializer = {
                        SiteFormInputPathsScreenViewModel(
                            origin = origin,
                            formInputRepository = formInputRepository,
                        )
                    })
                    val pathsUiState by pathsViewModel.uiState.collectAsState()
                    LaunchedEffect(pathsViewModel) {
                        pathsViewModel.eventHandler.receiveAsFlow().collect { handler ->
                            handler(object : SiteFormInputPathsScreenViewModel.Event {
                                override fun navigateBack() {
                                    outerBackStack.removeLastOrNull()
                                }

                                override fun navigateToPath(path: String) {
                                    outerBackStack.add(
                                        AppDestination.SiteFormInputPath(
                                            scheme = key.scheme,
                                            host = key.host,
                                            port = key.port,
                                            path = path,
                                        ),
                                    )
                                }
                            })
                        }
                    }
                    SiteFormInputPathsScreen(
                        uiState = pathsUiState,
                    )
                }

                is AppDestination.SiteFormInputPath -> navEntry(key) {
                    val formInputRepository: FormInputRepository = koinInject()
                    val origin = FormInputOrigin(
                        scheme = key.scheme,
                        host = key.host,
                        port = key.port,
                    )
                    val pathViewModel = composeViewModel(initializer = {
                        SiteFormInputPathScreenViewModel(
                            origin = origin,
                            path = key.path,
                            formInputRepository = formInputRepository,
                        )
                    })
                    val pathUiState by pathViewModel.uiState.collectAsState()
                    LaunchedEffect(pathViewModel) {
                        pathViewModel.eventHandler.receiveAsFlow().collect { handler ->
                            handler(object : SiteFormInputPathScreenViewModel.Event {
                                override fun navigateBack() {
                                    outerBackStack.removeLastOrNull()
                                }

                                override fun navigateBackAfterDeleted() {
                                    outerBackStack.removeLastOrNull()
                                }

                                override fun navigateToField(fieldKey: String) {
                                    outerBackStack.add(
                                        AppDestination.SiteFormInputField(
                                            scheme = key.scheme,
                                            host = key.host,
                                            port = key.port,
                                            path = key.path,
                                            fieldKey = fieldKey,
                                        ),
                                    )
                                }
                            })
                        }
                    }
                    SiteFormInputPathScreen(
                        uiState = pathUiState,
                    )
                }

                is AppDestination.SiteFormInputField -> navEntry(key) {
                    val formInputRepository: FormInputRepository = koinInject()
                    val origin = FormInputOrigin(
                        scheme = key.scheme,
                        host = key.host,
                        port = key.port,
                    )
                    val fieldViewModel = composeViewModel(initializer = {
                        SiteFormInputFieldScreenViewModel(
                            origin = origin,
                            path = key.path,
                            fieldKey = key.fieldKey,
                            formInputRepository = formInputRepository,
                        )
                    })
                    val fieldUiState by fieldViewModel.uiState.collectAsState()
                    LaunchedEffect(fieldViewModel) {
                        fieldViewModel.eventHandler.receiveAsFlow().collect { handler ->
                            handler(object : SiteFormInputFieldScreenViewModel.Event {
                                override fun navigateBack() {
                                    outerBackStack.removeLastOrNull()
                                }

                                override fun navigateBackAfterDeleted() {
                                    outerBackStack.removeLastOrNull()
                                }
                            })
                        }
                    }
                    SiteFormInputFieldScreen(
                        uiState = fieldUiState,
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
                                    // null のモードでは URL を開けないため、画面だけ閉じないよう何もしない
                                    val navigateToUrl = onNavigateToUrl ?: return
                                    scope.launch {
                                        navigateToUrl(url)
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

                AppDestination.Addresses -> navEntry(key) {
                    val addressRepository: AddressRepository = koinInject()
                    val addressesViewModel = composeViewModel(initializer = {
                        AddressesScreenViewModel(addressRepository)
                    })
                    val addressesUiState by addressesViewModel.uiState.collectAsState()
                    LaunchedEffect(addressesViewModel) {
                        addressesViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : AddressesScreenViewModel.Event {
                                override fun navigateToEdit(addressId: Long) {
                                    outerBackStack.add(AppDestination.AddressEdit(addressId = addressId))
                                }
                            })
                        }
                    }
                    AddressesScreen(
                        uiState = addressesUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                AppDestination.CrashLogs -> navEntry(key) {
                    val crashLogRepository: CrashLogRepository = koinInject()
                    val crashLogsViewModel = composeViewModel(initializer = {
                        CrashLogsScreenViewModel(crashLogRepository)
                    })
                    val crashLogsUiState by crashLogsViewModel.uiState.collectAsState()
                    LaunchedEffect(crashLogsViewModel) {
                        crashLogsViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : CrashLogsScreenViewModel.Event {
                                override fun navigateToDetail(crashLogId: Long) {
                                    outerBackStack.add(AppDestination.CrashLogDetail(crashLogId = crashLogId))
                                }
                            })
                        }
                    }
                    CrashLogsRoute(
                        uiState = crashLogsUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                is AppDestination.CrashLogDetail -> navEntry(key) {
                    val crashLogRepository: CrashLogRepository = koinInject()
                    val crashLogDetailViewModel = composeViewModel(initializer = {
                        CrashLogDetailScreenViewModel(
                            crashLogRepository = crashLogRepository,
                            crashLogId = key.crashLogId,
                        )
                    })
                    val crashLogDetailUiState by crashLogDetailViewModel.uiState.collectAsState()
                    LaunchedEffect(crashLogDetailViewModel) {
                        crashLogDetailViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : CrashLogDetailScreenViewModel.Event {
                                override fun copyToClipboard(text: String) {
                                    copyTextToClipboard(
                                        context = context,
                                        label = "crash log",
                                        text = text,
                                        message = "クラッシュログをコピーしました",
                                    )
                                }
                            })
                        }
                    }
                    CrashLogDetailRoute(
                        uiState = crashLogDetailUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                is AppDestination.AddressEdit -> navEntry(key) {
                    val addressRepository: AddressRepository = koinInject()
                    val addressEditViewModel = composeViewModel(initializer = {
                        AddressEditScreenViewModel(
                            addressRepository = addressRepository,
                            addressId = key.addressId,
                        )
                    })
                    val addressEditUiState by addressEditViewModel.uiState.collectAsState()
                    LaunchedEffect(addressEditViewModel) {
                        addressEditViewModel.eventHandler.receiveAsFlow().collect {
                            it(object : AddressEditScreenViewModel.Event {
                                override fun navigateBack() {
                                    outerBackStack.removeLastOrNull()
                                }
                            })
                        }
                    }
                    AddressEditScreen(
                        uiState = addressEditUiState,
                        onBack = { outerBackStack.removeLastOrNull() },
                    )
                }

                AppDestination.Extensions -> navEntry(key) {
                    val extensionRuntimeCoordinator: ExtensionRuntimeCoordinator = koinInject()
                    val extensionsViewModel = composeViewModel(initializer = {
                        ExtensionsScreenViewModel(
                            application = context.applicationContext as Application,
                            runtime = runtime,
                            settingsRepository = settingsRepository,
                            extensionRuntimeCoordinator = extensionRuntimeCoordinator,
                        )
                    })
                    val extensionsUiState by extensionsViewModel.uiState.collectAsState()
                    val extensionFileLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        extensionsViewModel.onExtensionFileSelected(uri)
                    }
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

                                override fun requestExtensionFilePicker() {
                                    extensionFileLauncher.launch(
                                        ExtensionsScreenViewModel.EXTENSION_ARCHIVE_MIME_TYPES,
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
                                    // null のモードでは URL を開けないため、画面だけ閉じないよう何もしない
                                    val navigateToUrl = onNavigateToUrl ?: return
                                    scope.launch {
                                        navigateToUrl(url)
                                        while (outerBackStack.size > 1) {
                                            outerBackStack.removeLastOrNull()
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
                        onBack = { outerBackStack.removeLastOrNull() },
                        highlightItemId = highlightItemId,
                        onHighlightComplete = { highlightItemId = null },
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
                                    Process.killProcess(Process.myPid())
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
    fun add(destination: AppDestination) {
        backStack.add(destination)
    }

    fun addIfAbsent(destination: AppDestination) {
        if (backStack.none { it == destination }) backStack.add(destination)
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
    newTabUrlFlow: Flow<NewTabRequest>,
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
                initialReferrerUrl = request.referrerUrl,
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
                            })
                        }
                    }
                    BrowserScreen(
                        tabId = key.tabId,
                        homepageUrl = uiState.homepageUrl,
                        uiState = browserScreenUiState,
                        browserTabController = browserTabController,
                        previewHeaderContent = { modifier, tab, tabCount ->
                            BrowserToolbar(
                                modifier = modifier,
                                toolbarColor = tab.themeColor?.let { Color(it) },
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
                                onHistoryRecord = browserScreenUiState.callbacks::onHistoryRecord,
                                onHistoryTitleUpdate = browserScreenUiState.callbacks::onHistoryTitleUpdate,
                                urlBarSuggestions = browserScreenUiState.urlBarSuggestions,
                                onUrlInputChanged = browserScreenUiState.callbacks::onUrlInputChanged,
                                onSessionDetachedFromView = {
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

// ──────────────────────────────────────────────────────────────
// ユーティリティ
// ──────────────────────────────────────────────────────────────

private const val GITHUB_RELEASES_URL = "https://github.com/matsudamper/browsem/releases"

private fun buildBackupFileName(): String {
    val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    return "browsem-backup-${formatter.format(java.util.Date())}.${BackupRepository.FILE_EXTENSION}"
}

private fun copyTextToClipboard(
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

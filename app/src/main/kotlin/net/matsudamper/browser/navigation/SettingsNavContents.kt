package net.matsudamper.browser.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.matsudamper.browser.CustomTabActivity
import net.matsudamper.browser.DefaultBrowserChecker
import net.matsudamper.browser.ExtensionSettingsScreen
import net.matsudamper.browser.GITHUB_RELEASES_URL
import net.matsudamper.browser.OuterNavActions
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionSettingsScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.ui.downloads.DownloadManagementScreen
import net.matsudamper.browser.ui.extensions.ExtensionsScreen
import net.matsudamper.browser.ui.settings.SettingsScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun SettingsNavContent(navActions: OuterNavActions) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsViewModel: SettingsScreenViewModel = koinViewModel()
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
                    navActions.add(AppDestination.BackupProgress(isImport))
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
            onOpenExtensions = { navActions.add(AppDestination.Extensions) },
            onOpenHistory = { navActions.add(AppDestination.History) },
            onOpenAddresses = { navActions.add(AppDestination.Addresses) },
            onOpenSiteSettings = { navActions.add(AppDestination.SiteSettingsList) },
            onOpenCrashLogs = { navActions.add(AppDestination.CrashLogs) },
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
            onBack = { navActions.pop() },
        )
    }
}

@Composable
internal fun ExtensionsNavContent(navActions: OuterNavActions) {
    val context = LocalContext.current
    val extensionsViewModel: ExtensionsScreenViewModel = koinViewModel()
    val extensionsUiState by extensionsViewModel.uiState.collectAsState()
    val extensionFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        extensionsViewModel.onExtensionFileSelected(uri)
    }
    LaunchedEffect(extensionsViewModel) {
        extensionsViewModel.eventHandler.receiveAsFlow().collect {
            it(object : ExtensionsScreenViewModel.Event {
                override fun navigateToExtensionSettings(extensionName: String, url: String) {
                    navActions.add(
                        AppDestination.ExtensionSettings(
                            extensionName = extensionName,
                            optionsPageUrl = url,
                        ),
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
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun ExtensionSettingsNavContent(
    key: AppDestination.ExtensionSettings,
    navActions: OuterNavActions,
    onNavigateToUrl: (suspend (url: String) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ExtensionSettingsScreenViewModel = koinViewModel {
        parametersOf(key.extensionName, key.optionsPageUrl)
    }
    val uiState by viewModel.uiState.collectAsState()
    ExtensionSettingsScreen(
        uiState = uiState,
        onBack = { navActions.pop() },
        onOpenExternalUrl = { url ->
            if (onNavigateToUrl != null) {
                scope.launch { onNavigateToUrl(url) }
            } else {
                // タブを持たない WebApp / カスタムタブから開いた場合はカスタムタブで開く
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(url),
                        context,
                        CustomTabActivity::class.java,
                    ),
                )
            }
        },
    )
}

@Composable
internal fun DownloadsNavContent(
    navActions: OuterNavActions,
    openRequest: PendingDownloadsOpenRequest,
    onOpenDownloadsRequestConsumed: ((String) -> Unit)?,
    onNavigateToUrl: (suspend (url: String) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val downloadsViewModel: DownloadManagementScreenViewModel = koinViewModel()
    val downloadsUiState by downloadsViewModel.uiState.collectAsState()
    val currentOnOpenDownloadsRequestConsumed by rememberUpdatedState(onOpenDownloadsRequestConsumed)
    var highlightItemIdString by rememberSaveable { mutableStateOf<String?>(null) }
    val highlightItemId = highlightItemIdString?.let { id ->
        runCatching { UUID.fromString(id) }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose {
            val entries = openRequest.consumeByWorkerIdEntries.value
            if (entries.isNotEmpty()) {
                openRequest.consumeByWorkerIdEntries.value = listOf()
                entries.forEach { (_, requestId) ->
                    currentOnOpenDownloadsRequestConsumed?.invoke(requestId)
                }
            }
        }
    }
    LaunchedEffect(
        openRequest.isRequested.value,
        openRequest.highlightWorkerId.value,
        openRequest.requestId.value,
    ) {
        if (!openRequest.isRequested.value) return@LaunchedEffect
        val workerId = openRequest.highlightWorkerId.value
        val requestId = openRequest.requestId.value
        openRequest.highlightWorkerId.value = null
        openRequest.requestId.value = null
        openRequest.isRequested.value = false
        if (workerId != null) {
            val id = runCatching { UUID.fromString(workerId) }.getOrNull()
            if (id != null) {
                downloadsViewModel.requestHighlight(id)
                if (requestId != null) {
                    openRequest.consumeByWorkerIdEntries.value =
                        openRequest.consumeByWorkerIdEntries.value + (workerId to requestId)
                }
                return@LaunchedEffect
            }
        }
        if (requestId != null) {
            currentOnOpenDownloadsRequestConsumed?.invoke(requestId)
        }
    }
    LaunchedEffect(downloadsViewModel) {
        downloadsViewModel.eventHandler.receiveAsFlow().collect {
            it(object : DownloadManagementScreenViewModel.Event {
                override fun navigateToUrl(url: String) {
                    // null のモードでは URL を開けないため、画面だけ閉じないよう何もしない
                    val navigateToUrl = onNavigateToUrl ?: return
                    scope.launch {
                        navigateToUrl(url)
                        navActions.popToRoot()
                    }
                }

                override fun highlightItem(id: UUID) {
                    highlightItemIdString = id.toString()
                }
            })
        }
    }
    DownloadManagementScreen(
        uiState = downloadsUiState,
        onBack = { navActions.pop() },
        highlightItemId = highlightItemId,
        onHighlightComplete = { itemId ->
            highlightItemIdString = null
            val workerId = itemId.toString()
            val requestId = openRequest.consumeByWorkerIdEntries.value
                .firstOrNull { it.first == workerId }
                ?.second
            if (requestId != null) {
                openRequest.consumeByWorkerIdEntries.value =
                    openRequest.consumeByWorkerIdEntries.value.filterNot { it.first == workerId }
                currentOnOpenDownloadsRequestConsumed?.invoke(requestId)
            }
        },
    )
}

/**
 * 通知などから届いたダウンロード画面の表示要求。
 * 要求を受ける [net.matsudamper.browser.BrowserAppShell] と、消費する [DownloadsNavContent] で共有する。
 */
internal class PendingDownloadsOpenRequest(
    val isRequested: MutableState<Boolean>,
    val highlightWorkerId: MutableState<String?>,
    val requestId: MutableState<String?>,
    val consumeByWorkerIdEntries: MutableState<List<Pair<String, String>>>,
)

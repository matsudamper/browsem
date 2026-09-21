package net.matsudamper.browser.navigation

import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import kotlinx.coroutines.flow.receiveAsFlow
import net.matsudamper.browser.BrowserSessionLifecycleController
import net.matsudamper.browser.BrowserTabController
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.OuterNavActions
import net.matsudamper.browser.buildBackupFileName
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.screen.backup.BackupProgressViewModel
import net.matsudamper.browser.ui.settings.backup.BackupProgressScreen
import net.matsudamper.browser.ui.settings.backup.BackupProgressUiState
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun BackupProgressNavContent(
    key: AppDestination.BackupProgress,
    navActions: OuterNavActions,
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
) {
    val context = LocalContext.current
    val backupViewModel: BackupProgressViewModel = koinViewModel { parametersOf(key.isImport) }
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
            val tabs = browserTabController.tabs
            tabs
                .filter { it.tabId in pausedTabIds }
                .forEach { tab ->
                    browserSessionLifecycleController.resumeSession(tab, tabs)
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
            navActions.pop()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            backupViewModel.startWithUri(uri)
        } else {
            navActions.pop()
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
                    navActions.pop()
                }
            })
        }
    }

    BackupProgressScreen(
        uiState = backupUiState,
    )
}

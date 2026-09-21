package net.matsudamper.browser.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.matsudamper.browser.OuterNavActions
import net.matsudamper.browser.copyTextToClipboard
import net.matsudamper.browser.screen.addresses.AddressEditScreenViewModel
import net.matsudamper.browser.screen.addresses.AddressesScreenViewModel
import net.matsudamper.browser.screen.crashlog.CrashLogDetailScreenViewModel
import net.matsudamper.browser.screen.crashlog.CrashLogsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.ui.history.HistoryScreen
import net.matsudamper.browser.ui.settings.address.AddressEditScreen
import net.matsudamper.browser.ui.settings.address.AddressesScreen
import net.matsudamper.browser.ui.settings.crash.CrashLogDetailRoute
import net.matsudamper.browser.ui.settings.crash.CrashLogsRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun HistoryNavContent(
    navActions: OuterNavActions,
    onNavigateToUrl: (suspend (url: String) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val historyViewModel: HistoryScreenViewModel = koinViewModel()
    val historyUiState by historyViewModel.uiState.collectAsState()
    LaunchedEffect(historyViewModel) {
        historyViewModel.eventHandler.receiveAsFlow().collect {
            it(object : HistoryScreenViewModel.Event {
                override fun navigateToUrl(url: String) {
                    // null のモードでは URL を開けないため、画面だけ閉じないよう何もしない
                    val navigateToUrl = onNavigateToUrl ?: return
                    scope.launch {
                        navigateToUrl(url)
                        navActions.popToRoot()
                    }
                }
            })
        }
    }
    HistoryScreen(
        uiState = historyUiState,
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun AddressesNavContent(navActions: OuterNavActions) {
    val addressesViewModel: AddressesScreenViewModel = koinViewModel()
    val addressesUiState by addressesViewModel.uiState.collectAsState()
    LaunchedEffect(addressesViewModel) {
        addressesViewModel.eventHandler.receiveAsFlow().collect {
            it(object : AddressesScreenViewModel.Event {
                override fun navigateToEdit(addressId: Long) {
                    navActions.add(AppDestination.AddressEdit(addressId = addressId))
                }
            })
        }
    }
    AddressesScreen(
        uiState = addressesUiState,
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun AddressEditNavContent(
    key: AppDestination.AddressEdit,
    navActions: OuterNavActions,
) {
    val addressEditViewModel: AddressEditScreenViewModel = koinViewModel {
        parametersOf(key.addressId)
    }
    val addressEditUiState by addressEditViewModel.uiState.collectAsState()
    LaunchedEffect(addressEditViewModel) {
        addressEditViewModel.eventHandler.receiveAsFlow().collect {
            it(object : AddressEditScreenViewModel.Event {
                override fun navigateBack() {
                    navActions.pop()
                }
            })
        }
    }
    AddressEditScreen(
        uiState = addressEditUiState,
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun CrashLogsNavContent(navActions: OuterNavActions) {
    val crashLogsViewModel: CrashLogsScreenViewModel = koinViewModel()
    val crashLogsUiState by crashLogsViewModel.uiState.collectAsState()
    LaunchedEffect(crashLogsViewModel) {
        crashLogsViewModel.eventHandler.receiveAsFlow().collect {
            it(object : CrashLogsScreenViewModel.Event {
                override fun navigateToDetail(crashLogId: Long) {
                    navActions.add(AppDestination.CrashLogDetail(crashLogId = crashLogId))
                }
            })
        }
    }
    CrashLogsRoute(
        uiState = crashLogsUiState,
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun CrashLogDetailNavContent(
    key: AppDestination.CrashLogDetail,
    navActions: OuterNavActions,
) {
    val context = LocalContext.current
    val crashLogDetailViewModel: CrashLogDetailScreenViewModel = koinViewModel {
        parametersOf(key.crashLogId)
    }
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
        onBack = { navActions.pop() },
    )
}

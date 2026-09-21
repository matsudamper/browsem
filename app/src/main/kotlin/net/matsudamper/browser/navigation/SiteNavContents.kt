package net.matsudamper.browser.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.receiveAsFlow
import net.matsudamper.browser.BrowserTabController
import net.matsudamper.browser.OuterNavActions
import net.matsudamper.browser.data.forminput.FormInputOrigin
import net.matsudamper.browser.screen.siteforminput.SiteFormInputFieldScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputPathScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputPathsScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsListScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenParams
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenViewModel
import net.matsudamper.browser.ui.settings.form.SiteFormInputFieldScreen
import net.matsudamper.browser.ui.settings.form.SiteFormInputPathScreen
import net.matsudamper.browser.ui.settings.form.SiteFormInputPathsScreen
import net.matsudamper.browser.ui.settings.site.SiteSettingsListScreen
import net.matsudamper.browser.ui.settings.site.SiteSettingsScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun SiteSettingsListNavContent(navActions: OuterNavActions) {
    val siteSettingsListViewModel: SiteSettingsListScreenViewModel = koinViewModel()
    val siteSettingsListUiState by siteSettingsListViewModel.uiState.collectAsState()
    LaunchedEffect(siteSettingsListViewModel) {
        siteSettingsListViewModel.eventHandler.receiveAsFlow().collect { handler ->
            handler(object : SiteSettingsListScreenViewModel.Event {
                override fun navigateToSiteSettings(host: String) {
                    navActions.add(AppDestination.SiteSettings(host = host))
                }
            })
        }
    }
    SiteSettingsListScreen(
        uiState = siteSettingsListUiState,
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun SiteSettingsNavContent(
    key: AppDestination.SiteSettings,
    navActions: OuterNavActions,
    browserTabController: BrowserTabController,
) {
    val formInputOrigin = FormInputOrigin(
        scheme = key.scheme,
        host = key.host,
        port = key.port,
    )
    val params = remember(key, browserTabController) {
        SiteSettingsScreenParams(
            host = key.host,
            formInputOrigin = formInputOrigin,
            securityInfo = key.tabId
                ?.let { browserTabController.findTab(it) }
                ?.securityInfo,
        )
    }
    val siteSettingsViewModel: SiteSettingsScreenViewModel = koinViewModel { parametersOf(params) }
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
                    navActions.add(
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
        onBack = { navActions.pop() },
    )
}

@Composable
internal fun SiteFormInputPathsNavContent(
    key: AppDestination.SiteFormInputPaths,
    navActions: OuterNavActions,
) {
    val origin = FormInputOrigin(
        scheme = key.scheme,
        host = key.host,
        port = key.port,
    )
    val pathsViewModel: SiteFormInputPathsScreenViewModel = koinViewModel { parametersOf(origin) }
    val pathsUiState by pathsViewModel.uiState.collectAsState()
    LaunchedEffect(pathsViewModel) {
        pathsViewModel.eventHandler.receiveAsFlow().collect { handler ->
            handler(object : SiteFormInputPathsScreenViewModel.Event {
                override fun navigateBack() {
                    navActions.pop()
                }

                override fun navigateToPath(path: String) {
                    navActions.add(
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

@Composable
internal fun SiteFormInputPathNavContent(
    key: AppDestination.SiteFormInputPath,
    navActions: OuterNavActions,
) {
    val origin = FormInputOrigin(
        scheme = key.scheme,
        host = key.host,
        port = key.port,
    )
    val pathViewModel: SiteFormInputPathScreenViewModel = koinViewModel {
        parametersOf(origin, key.path)
    }
    val pathUiState by pathViewModel.uiState.collectAsState()
    LaunchedEffect(pathViewModel) {
        pathViewModel.eventHandler.receiveAsFlow().collect { handler ->
            handler(object : SiteFormInputPathScreenViewModel.Event {
                override fun navigateBack() {
                    navActions.pop()
                }

                override fun navigateBackAfterDeleted() {
                    navActions.pop()
                }

                override fun navigateToField(fieldKey: String) {
                    navActions.add(
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

@Composable
internal fun SiteFormInputFieldNavContent(
    key: AppDestination.SiteFormInputField,
    navActions: OuterNavActions,
) {
    val origin = FormInputOrigin(
        scheme = key.scheme,
        host = key.host,
        port = key.port,
    )
    val fieldViewModel: SiteFormInputFieldScreenViewModel = koinViewModel {
        parametersOf(origin, key.path, key.fieldKey)
    }
    val fieldUiState by fieldViewModel.uiState.collectAsState()
    LaunchedEffect(fieldViewModel) {
        fieldViewModel.eventHandler.receiveAsFlow().collect { handler ->
            handler(object : SiteFormInputFieldScreenViewModel.Event {
                override fun navigateBack() {
                    navActions.pop()
                }

                override fun navigateBackAfterDeleted() {
                    navActions.pop()
                }
            })
        }
    }
    SiteFormInputFieldScreen(
        uiState = fieldUiState,
    )
}

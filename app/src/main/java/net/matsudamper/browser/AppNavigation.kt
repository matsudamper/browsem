package net.matsudamper.browser

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import org.mozilla.geckoview.GeckoRuntime

private sealed interface AppDestination : NavKey {
    data object Browser : AppDestination
    data object Settings : AppDestination
}

@Composable
internal fun BrowserApp(
    runtime: GeckoRuntime,
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings
        .collectAsState(initial = BrowserSettings.getDefaultInstance())

    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(AppDestination.Browser)

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key: NavKey ->
            when (key) {
                AppDestination.Browser -> NavEntry<NavKey>(key) {
                    GeckoBrowserTab(
                        runtime = runtime,
                        homepageUrl = settings.resolvedHomepageUrl(),
                        searchTemplate = settings.resolvedSearchTemplate(),
                        onOpenSettings = {
                            backStack.add(AppDestination.Settings)
                        },
                    )
                }

                AppDestination.Settings -> NavEntry<NavKey>(key) {
                    SettingsScreen(
                        settings = settings,
                        onSettingsChange = { newSettings ->
                            scope.launch { settingsRepository.updateSettings(newSettings) }
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }

                else -> error("Unknown destination: $key")
            }
        },
    )
}

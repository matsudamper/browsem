package net.matsudamper.browser

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.mozilla.geckoview.GeckoRuntime

private sealed interface AppDestination : NavKey {
    data object Browser : AppDestination
    data object Settings : AppDestination
}

@Composable
internal fun BrowserApp(
    runtime: GeckoRuntime,
) {
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
                        onOpenSettings = {
                            backStack.add(AppDestination.Settings)
                        }
                    )
                }

                AppDestination.Settings -> NavEntry<NavKey>(key) {
                    SettingsScreen()
                }

                else -> error("Unknown destination: $key")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    TopAppBar(
        title = {
            Text("設定")
        }
    )
}

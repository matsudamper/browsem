package net.matsudamper.browser.ui.settings.webapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun WebAppsRoute(
    uiState: WebAppsScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WebAppsScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

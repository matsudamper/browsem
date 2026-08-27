package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SiteFormInputPathsRoute(
    uiState: SiteFormInputPathsScreenUiState,
    modifier: Modifier = Modifier,
) {
    SiteFormInputPathsScreen(
        uiState = uiState,
        modifier = modifier,
    )
}

@Composable
fun SiteFormInputPathRoute(
    uiState: SiteFormInputPathScreenUiState,
    modifier: Modifier = Modifier,
) {
    SiteFormInputPathScreen(
        uiState = uiState,
        modifier = modifier,
    )
}

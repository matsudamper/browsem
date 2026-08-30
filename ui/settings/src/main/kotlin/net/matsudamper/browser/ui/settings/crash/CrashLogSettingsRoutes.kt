package net.matsudamper.browser.ui.settings.crash

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CrashLogsRoute(
    uiState: CrashLogsScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CrashLogsScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun CrashLogDetailRoute(
    uiState: CrashLogDetailScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CrashLogDetailScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

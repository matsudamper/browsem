package net.matsudamper.browser

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * リダイレクト先の App Link をアプリで開く導線。
 * 遷移はブラウザで続けているため、ダイアログでページ操作を塞がずスナックバーで出す。
 */
@Composable
internal fun RedirectAppLinkSnackbar(
    request: PendingExternalAppLaunch?,
    onOpen: () -> Unit,
    onDismiss: (PendingExternalAppLaunch) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(request) {
        if (request != null) {
            val message = if (request.appName != null) {
                "${request.appName} で開けます"
            } else {
                "このページはアプリで開けます"
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "開く",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> latestOnOpen()
                SnackbarResult.Dismissed -> latestOnDismiss(request)
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    )
}

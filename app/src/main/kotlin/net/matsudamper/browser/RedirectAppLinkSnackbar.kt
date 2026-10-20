package net.matsudamper.browser

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme

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
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(request) {
        if (request != null) {
            delay(REDIRECT_APP_LINK_SNACKBAR_DURATION_MILLIS)
            latestOnDismiss(request)
        }
    }
    if (request != null) {
        RedirectAppLinkSnackbarContent(
            appName = request.appName,
            onOpen = onOpen,
            onDismiss = { onDismiss(request) },
            modifier = modifier,
        )
    }
}

@Composable
private fun RedirectAppLinkSnackbarContent(
    appName: String?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Snackbar(
        modifier = modifier.padding(12.dp),
        action = {
            TextButton(onClick = onOpen) {
                Text("開く")
            }
        },
        dismissAction = {
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(ResourcesR.drawable.close_24dp),
                    contentDescription = "閉じる",
                )
            }
        },
    ) {
        Text(
            if (appName != null) {
                "$appName で開けます"
            } else {
                "このページはアプリで開けます"
            },
        )
    }
}

private const val REDIRECT_APP_LINK_SNACKBAR_DURATION_MILLIS = 10_000L

@Preview(name = "アプリ名あり", widthDp = 360)
@Composable
private fun PreviewRedirectAppLinkSnackbarWithAppName() {
    BrowserTheme(themeMode = ThemeMode.THEME_LIGHT) {
        RedirectAppLinkSnackbarContent(
            appName = "マイナアプリ",
            onOpen = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "アプリ名なし", widthDp = 360)
@Composable
private fun PreviewRedirectAppLinkSnackbarWithoutAppName() {
    BrowserTheme(themeMode = ThemeMode.THEME_LIGHT) {
        RedirectAppLinkSnackbarContent(
            appName = null,
            onOpen = {},
            onDismiss = {},
        )
    }
}

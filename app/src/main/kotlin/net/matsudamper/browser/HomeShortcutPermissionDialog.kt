package net.matsudamper.browser

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme

@Composable
internal fun HomeShortcutPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ホームに追加する権限がありません") },
        text = {
            Text("設定のアプリの権限で「ホーム画面ショートカット」を許可してから、もう一度追加してください。")
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onOpenSettings()
                    onDismiss()
                },
            ) {
                Text("設定を開く")
            }
        },
    )
}

@Preview
@Composable
private fun PreviewHomeShortcutPermissionDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        HomeShortcutPermissionDialog(
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}

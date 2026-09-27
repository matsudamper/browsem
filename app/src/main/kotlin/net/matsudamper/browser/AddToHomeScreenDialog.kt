package net.matsudamper.browser

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme

/**
 * ホームへの追加方法を選択するダイアログ。
 * ショートカット（ブラウザで開く）とアプリとして追加の2択を提供する。
 */
@Composable
internal fun AddToHomeScreenDialog(
    url: String,
    title: String,
    favicon: Bitmap?,
    isIconLoading: Boolean,
    showShortcutOption: Boolean,
    onAddWebApp: (title: String) -> Unit,
    onAddShortcut: (title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editedTitle by remember { mutableStateOf(title.ifBlank { url }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ホームに追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it },
                    label = { Text("タイトル") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isIconLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("アイコンを取得中")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            Row {
                if (showShortcutOption) {
                    TextButton(
                        onClick = {
                            onAddShortcut(editedTitle)
                        },
                        enabled = !isIconLoading,
                    ) {
                        Text("ショートカット")
                    }
                }
                TextButton(
                    onClick = {
                        onAddWebApp(editedTitle)
                        onDismiss()
                    },
                    enabled = !isIconLoading,
                ) {
                    Text(if (showShortcutOption) "アプリ" else "追加")
                }
            }
        },
    )
}

@Preview(name = "favicon あり")
@Composable
private fun PreviewWithFavicon() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        AddToHomeScreenDialog(
            url = "https://example.com",
            title = "Example Site",
            favicon = null,
            isIconLoading = false,
            showShortcutOption = true,
            onAddWebApp = {},
            onAddShortcut = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "登録済みウェブアプリ")
@Composable
private fun PreviewRegisteredWebApp() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        AddToHomeScreenDialog(
            url = "https://example.com",
            title = "Example",
            favicon = null,
            isIconLoading = false,
            showShortcutOption = false,
            onAddWebApp = {},
            onAddShortcut = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "タイトルなし")
@Composable
private fun PreviewNoTitle() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        AddToHomeScreenDialog(
            url = "https://example.com/very/long/path?query=value",
            title = "",
            favicon = null,
            isIconLoading = true,
            showShortcutOption = true,
            onAddWebApp = {},
            onAddShortcut = {},
            onDismiss = {},
        )
    }
}

package net.matsudamper.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import org.json.JSONObject

/**
 * ホームへの追加方法を選択するダイアログ。
 * ショートカット（ブラウザで開く）とTWAアプリとして追加の2択を提供する。
 * ViewModelがマニフェスト解析・アイコンフェッチ・ショートカット作成を担当する。
 */
@Composable
internal fun AddToHomeScreenDialog(
    pageUrl: String,
    viewModel: AddToHomeScreenViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ホームに追加") },
        text = {
            Column {
                if (uiState.isPwa) {
                    Text("PWA")
                }
                Text(uiState.displayName)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        viewModel.addShortcutToHome(pageUrl)
                        onDismiss()
                    },
                ) {
                    Text("ショートカット")
                }
                TextButton(
                    onClick = {
                        viewModel.addWebAppToHome()
                        onDismiss()
                    },
                ) {
                    Text("アプリ")
                }
            }
        },
    )
}

@Preview(name = "通常サイト")
@Composable
private fun PreviewNormal() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        // Previewはダミーのステートで確認するため、ViewModelを直接使わずにモック的に表示
        AlertDialog(
            onDismissRequest = {},
            title = { Text("ホームに追加") },
            text = { Text("Example Site") },
            dismissButton = { TextButton(onClick = {}) { Text("キャンセル") } },
            confirmButton = {
                Row {
                    TextButton(onClick = {}) { Text("ショートカット") }
                    TextButton(onClick = {}) { Text("アプリ") }
                }
            },
        )
    }
}

@Preview(name = "PWAマニフェストあり")
@Composable
private fun PreviewWithPwa() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("ホームに追加") },
            text = {
                Column {
                    Text("PWA")
                    Text("Example PWA App")
                }
            },
            dismissButton = { TextButton(onClick = {}) { Text("キャンセル") } },
            confirmButton = {
                Row {
                    TextButton(onClick = {}) { Text("ショートカット") }
                    TextButton(onClick = {}) { Text("アプリ") }
                }
            },
        )
    }
}

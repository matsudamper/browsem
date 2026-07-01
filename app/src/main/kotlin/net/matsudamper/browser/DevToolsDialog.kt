package net.matsudamper.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme

/**
 * 開発者ツールのダイアログ。
 * タイトルの下にリストメニューを表示し、項目を押すと対応する値をクリップボードへコピーする。
 *
 * @param focusedInput フォーカス中の入力要素の情報。フォーカスがない場合は null。
 * @param onCopyFocusedInputId フォーカス中の input の id をコピーする。
 * @param onRefresh 最新のフォーカス情報を再取得する。
 * @param onDismiss ダイアログを閉じる。
 */
@Composable
internal fun DevToolsDialog(
    focusedInput: DevToolsWebExtension.FocusedInputInfo?,
    onCopyFocusedInputId: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    // フォーカス中の input の id（空文字は「なし」とみなす）
    val focusedId = focusedInput?.id?.takeIf { it.isNotBlank() }
    AlertDialog(
        modifier = Modifier.testTag(DevToolsDialogTestTags.Dialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("開発者ツール") },
        text = {
            // 現状メニュー項目は「フォーカス中の input id」の 1 件のみ。押すとコピーする。
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = focusedId != null, onClick = onCopyFocusedInputId)
                    .testTag(DevToolsDialogTestTags.FocusedInputId.testTag),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("フォーカス中の input id をコピー") },
                supportingContent = {
                    Text(
                        text = focusedId ?: "フォーカスされている入力要素はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onRefresh) {
                Text("更新")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

sealed interface DevToolsDialogTestTags {
    val id: String
    val testTag get() = "${DevToolsDialogTestTags::class.java.name}#$id"

    object Dialog : DevToolsDialogTestTags { override val id = "dialog" }
    object FocusedInputId : DevToolsDialogTestTags { override val id = "focused_input_id" }
}

@Preview(name = "input フォーカスあり")
@Composable
private fun PreviewDevToolsDialogFocused() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsDialog(
            focusedInput = DevToolsWebExtension.FocusedInputInfo(
                id = "search-box",
                tagName = "input",
                type = "text",
                name = "q",
            ),
            onCopyFocusedInputId = {},
            onRefresh = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "フォーカスなし")
@Composable
private fun PreviewDevToolsDialogNoFocus() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsDialog(
            focusedInput = null,
            onCopyFocusedInputId = {},
            onRefresh = {},
            onDismiss = {},
        )
    }
}

package net.matsudamper.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import net.matsudamper.browser.feature.devtools.DevToolsWebExtension
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme

private enum class DevToolsDialogPage {
    Menu,
    FocusedInputId,
}

/**
 * 開発者ツールのダイアログ。
 * タイトルの下に項目名だけのリストメニューを表示する。
 *
 * @param focusedInput フォーカス中の入力要素の情報。フォーカスがない場合は null。
 * @param onCopyFocusedInputId フォーカス中の input の id をコピーする。
 * @param onOpenNetworkLog ネットワークログ画面を開く。
 * @param onDismiss ダイアログを閉じる。
 */
@Composable
internal fun DevToolsDialog(
    focusedInput: DevToolsWebExtension.FocusedInputInfo?,
    onCopyFocusedInputId: () -> Unit,
    onOpenNetworkLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    // フォーカス中の input の id（空文字は「なし」とみなす）
    val focusedId = focusedInput?.id?.takeIf { it.isNotBlank() }
    var page by remember { mutableStateOf(DevToolsDialogPage.Menu) }

    when (page) {
        DevToolsDialogPage.Menu -> {
            DevToolsMenuDialog(
                focusedId = focusedId,
                onOpenFocusedInputId = {
                    if (focusedId != null) {
                        page = DevToolsDialogPage.FocusedInputId
                    }
                },
                onOpenNetworkLog = onOpenNetworkLog,
                onDismiss = onDismiss,
            )
        }
        DevToolsDialogPage.FocusedInputId -> {
            FocusedInputIdDialog(
                focusedId = focusedId ?: return,
                onCopyFocusedInputId = onCopyFocusedInputId,
                onBack = { page = DevToolsDialogPage.Menu },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun DevToolsMenuDialog(
    focusedId: String?,
    onOpenFocusedInputId: () -> Unit,
    onOpenNetworkLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(DevToolsDialogTestTags.Dialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("開発者ツール") },
        text = {
            Column {
                // 押すとフォーカス中の input id の確認画面を開く
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = focusedId != null, onClick = onOpenFocusedInputId)
                        .testTag(DevToolsDialogTestTags.FocusedInputId.testTag),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("フォーカス中の input id") },
                    supportingContent = {
                        Text(
                            text = focusedId ?: "フォーカスされている入力要素はありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                // 押すとページが行った通信の一覧を開く
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNetworkLog)
                        .testTag(DevToolsDialogTestTags.NetworkLog.testTag),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("ネットワークログ") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun FocusedInputIdDialog(
    focusedId: String,
    onCopyFocusedInputId: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(DevToolsDialogTestTags.FocusedInputIdDialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("フォーカス中の input id") },
        text = {
            Text(
                modifier = Modifier.testTag(DevToolsDialogTestTags.FocusedInputIdValue.testTag),
                text = focusedId,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(DevToolsDialogTestTags.CopyFocusedInputIdButton.testTag),
                onClick = onCopyFocusedInputId,
            ) {
                Text("コピー")
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text("戻る")
            }
        },
    )
}

sealed interface DevToolsDialogTestTags {
    val id: String
    val testTag get() = "${DevToolsDialogTestTags::class.java.name}#$id"

    object Dialog : DevToolsDialogTestTags { override val id = "dialog" }
    object FocusedInputId : DevToolsDialogTestTags { override val id = "focused_input_id" }
    object FocusedInputIdDialog : DevToolsDialogTestTags { override val id = "focused_input_id_dialog" }
    object FocusedInputIdValue : DevToolsDialogTestTags { override val id = "focused_input_id_value" }
    object CopyFocusedInputIdButton : DevToolsDialogTestTags { override val id = "copy_focused_input_id_button" }
    object NetworkLog : DevToolsDialogTestTags { override val id = "network_log" }
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
            onOpenNetworkLog = {},
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
            onOpenNetworkLog = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "input id 確認")
@Composable
private fun PreviewFocusedInputIdDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        FocusedInputIdDialog(
            focusedId = "search-box",
            onCopyFocusedInputId = {},
            onBack = {},
            onDismiss = {},
        )
    }
}

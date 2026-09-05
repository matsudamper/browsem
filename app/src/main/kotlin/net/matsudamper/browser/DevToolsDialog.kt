package net.matsudamper.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.feature.devtools.DevToolsWebExtension
import net.matsudamper.browser.ui.common.BrowserTheme

/**
 * 開発者ツールのダイアログ。
 * タイトルの下に項目名だけのリストメニューを表示する。
 *
 * @param focusedInput フォーカス中の入力要素の情報。フォーカスがない場合は null。
 * @param onCopyFocusedInputId フォーカス中の input の id をコピーする。
 * @param onOpenNetworkLog ネットワークログ画面を開く。
 * @param onOpenConsoleLog コンソールログ画面を開く。
 * @param onOpenScriptRunner スクリプト実行画面を開く。
 * @param onDismiss ダイアログを閉じる。
 */
@Composable
internal fun DevToolsDialog(
    focusedInput: DevToolsWebExtension.FocusedInputInfo?,
    onCopyFocusedInputId: () -> Unit,
    onOpenNetworkLog: () -> Unit,
    onOpenConsoleLog: () -> Unit,
    onOpenScriptRunner: () -> Unit,
    onDismiss: () -> Unit,
) {
    // フォーカス中の input の id（空文字は「なし」とみなす）
    val focusedId = focusedInput?.id?.takeIf { it.isNotBlank() }
    AlertDialog(
        modifier = Modifier.testTag(DevToolsDialogTestTags.Dialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("開発者ツール") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 押すとフォーカス中のinput idをコピーする
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = focusedId != null, onClick = onCopyFocusedInputId)
                        .testTag(DevToolsDialogTestTags.FocusedInputId.testTag),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("フォーカス中のinput idをコピー") },
                    supportingContent = {
                        Text(
                            text = focusedId ?: "なし",
                            style = MaterialTheme.typography.bodySmall,
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
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenConsoleLog)
                        .testTag(DevToolsDialogTestTags.ConsoleLog.testTag),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("コンソールログ") },
                )
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenScriptRunner)
                        .testTag(DevToolsDialogTestTags.ScriptRunner.testTag),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("スクリプト実行") },
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

sealed interface DevToolsDialogTestTags {
    val id: String
    val testTag get() = "${DevToolsDialogTestTags::class.java.name}#$id"

    object Dialog : DevToolsDialogTestTags {
        override val id = "dialog"
    }
    object FocusedInputId : DevToolsDialogTestTags {
        override val id = "focused_input_id"
    }
    object NetworkLog : DevToolsDialogTestTags {
        override val id = "network_log"
    }
    object ConsoleLog : DevToolsDialogTestTags {
        override val id = "console_log"
    }
    object ScriptRunner : DevToolsDialogTestTags {
        override val id = "script_runner"
    }
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
            onOpenConsoleLog = {},
            onOpenScriptRunner = {},
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
            onOpenConsoleLog = {},
            onOpenScriptRunner = {},
            onDismiss = {},
        )
    }
}

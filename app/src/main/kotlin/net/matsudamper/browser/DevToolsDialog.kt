package net.matsudamper.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.ui.common.BrowserTheme

/**
 * 開発者ツールのダイアログ。
 * 現在フォーカスされている入力要素の情報（id など）を表示する。
 *
 * @param focusedInput フォーカス中の入力要素の情報。フォーカスがない場合は null。
 * @param onRefresh 最新のフォーカス情報を再取得する。
 * @param onDismiss ダイアログを閉じる。
 */
@Composable
internal fun DevToolsDialog(
    focusedInput: DevToolsWebExtension.FocusedInputInfo?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(DevToolsDialogTestTags.Dialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("開発者ツール") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "フォーカス中の input",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (focusedInput == null) {
                    Text(
                        modifier = Modifier.testTag(DevToolsDialogTestTags.FocusedInputId.testTag),
                        text = "フォーカスされている入力要素はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DevToolsInfoRow(
                        label = "id",
                        value = focusedInput.id.ifBlank { "(なし)" },
                        valueTestTag = DevToolsDialogTestTags.FocusedInputId.testTag,
                    )
                    DevToolsInfoRow(label = "tag", value = focusedInput.tagName)
                    if (focusedInput.type.isNotBlank()) {
                        DevToolsInfoRow(label = "type", value = focusedInput.type)
                    }
                    if (focusedInput.name.isNotBlank()) {
                        DevToolsInfoRow(label = "name", value = focusedInput.name)
                    }
                }
            }
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

@Composable
private fun DevToolsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTestTag: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        DevToolsDialog(
            focusedInput = DevToolsWebExtension.FocusedInputInfo(
                id = "search-box",
                tagName = "input",
                type = "text",
                name = "q",
            ),
            onRefresh = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "フォーカスなし")
@Composable
private fun PreviewDevToolsDialogNoFocus() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        DevToolsDialog(
            focusedInput = null,
            onRefresh = {},
            onDismiss = {},
        )
    }
}

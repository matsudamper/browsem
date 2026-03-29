package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.TabGroupData

/**
 * タブの移動先グループを選択するダイアログ。
 * 現在所属しているグループ以外のグループを一覧表示し、
 * タップで選択するとそのグループへタブを移動する。
 */
@Composable
internal fun MoveTabToGroupDialog(
    groups: List<TabGroupData>,
    currentGroupIndex: Int,
    onGroupSelected: (targetGroupIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("グループに移動")
        },
        text = {
            Column {
                groups.forEachIndexed { index, group ->
                    if (index == currentGroupIndex) return@forEachIndexed
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGroupSelected(index) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

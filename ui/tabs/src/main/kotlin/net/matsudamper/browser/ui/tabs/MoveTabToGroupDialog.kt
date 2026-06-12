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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.TabGroupData
import net.matsudamper.browser.data.TabGroupId

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
                    // 片手で持っていても押しやすいように全幅・中央寄せにする
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
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
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
@Preview
private fun PreviewMoveTabToGroupDialog() {
    MoveTabToGroupDialog(
        groups = listOf(
            TabGroupData(TabGroupId("g1"), "デフォルト"),
            TabGroupData(TabGroupId("g2"), "開発"),
            TabGroupData(TabGroupId("g3"), "調べ物"),
        ),
        currentGroupIndex = 0,
        onGroupSelected = {},
        onDismiss = {},
    )
}

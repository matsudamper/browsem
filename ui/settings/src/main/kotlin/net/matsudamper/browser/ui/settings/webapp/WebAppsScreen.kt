package net.matsudamper.browser.ui.settings.webapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.StatusBarAppearanceEffect

sealed interface WebAppsScreenTestTags {
    val id: String

    val testTag get() = "${WebAppsScreenTestTags::class.java.name}#$id"

    data object Root : WebAppsScreenTestTags {
        override val id = "root"
    }

    data class Entry(val index: Int) : WebAppsScreenTestTags {
        override val id = "entry_$index"
    }

    data object RenameConfirmButton : WebAppsScreenTestTags {
        override val id = "rename_confirm_button"
    }

    data class DeleteButton(val index: Int) : WebAppsScreenTestTags {
        override val id = "delete_button_$index"
    }

    data object ConfirmDeleteButton : WebAppsScreenTestTags {
        override val id = "confirm_delete_button"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebAppsScreen(
    uiState: WebAppsScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusBarAppearanceEffect(MaterialTheme.colorScheme.surface)
    Scaffold(
        modifier = modifier.testTag(WebAppsScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text("ホームに追加したアプリ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.entries.isEmpty() -> {
                Text(
                    text = "ホームに追加したアプリはありません",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(16.dp),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                ) {
                    itemsIndexed(uiState.entries) { index, entry ->
                        WebAppListItem(entry = entry, index = index)
                    }
                }
            }
        }
    }

    val renameDialog = uiState.renameDialog
    if (renameDialog != null) {
        WebAppRenameDialog(renameDialog = renameDialog)
    }

    val deleteConfirmDialog = uiState.deleteConfirmDialog
    if (deleteConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = deleteConfirmDialog.listener::onDismiss,
            title = { Text("確認") },
            text = { Text("「${deleteConfirmDialog.title}」を削除しますか？ホームのアイコンは「削除済み」になり使えなくなります。アイコン自体は長押しでホームから削除してください。") },
            confirmButton = {
                TextButton(
                    onClick = deleteConfirmDialog.listener::onConfirm,
                    modifier = Modifier.testTag(WebAppsScreenTestTags.ConfirmDeleteButton.testTag),
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = deleteConfirmDialog.listener::onDismiss) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun WebAppRenameDialog(renameDialog: WebAppsScreenUiState.RenameDialog) {
    var text by remember(renameDialog.currentTitle) { mutableStateOf(renameDialog.currentTitle) }
    AlertDialog(
        onDismissRequest = renameDialog.listener::onDismiss,
        title = { Text("名前を変更") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("名前") },
                supportingText = { Text("ホームのアイコンの名前も変わります") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { renameDialog.listener.onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(WebAppsScreenTestTags.RenameConfirmButton.testTag),
            ) {
                Text("変更")
            }
        },
        dismissButton = {
            TextButton(onClick = renameDialog.listener::onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun WebAppListItem(
    entry: WebAppsScreenUiState.EntryItem,
    index: Int,
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = entry.startUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "プロファイル: ${entry.profileName}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            IconButton(
                onClick = entry.listener::onClickDelete,
                modifier = Modifier.testTag(WebAppsScreenTestTags.DeleteButton(index).testTag),
            ) {
                Icon(
                    painter = painterResource(ResourcesR.drawable.ic_delete_24dp),
                    contentDescription = "削除",
                )
            }
        },
        modifier = Modifier
            .testTag(WebAppsScreenTestTags.Entry(index).testTag)
            .clickable(onClick = entry.listener::onClick),
    )
}

private object PreviewEntryListener : WebAppsScreenUiState.EntryItem.Listener {
    override fun onClick() = Unit
    override fun onClickDelete() = Unit
}

private object PreviewRenameDialogListener : WebAppsScreenUiState.RenameDialog.Listener {
    override fun onConfirm(title: String) = Unit
    override fun onDismiss() = Unit
}

private object PreviewDeleteConfirmDialogListener : WebAppsScreenUiState.DeleteConfirmDialog.Listener {
    override fun onConfirm() = Unit
    override fun onDismiss() = Unit
}

private val previewEntries = listOf(
    WebAppsScreenUiState.EntryItem(
        title = "Example",
        startUrl = "https://example.com/",
        profileName = "デフォルト",
        listener = PreviewEntryListener,
    ),
    WebAppsScreenUiState.EntryItem(
        title = "とても長いタイトルのウェブアプリケーションの名前がここに入ります",
        startUrl = "https://example.com/very/long/path?query=value&another=value",
        profileName = "仕事",
        listener = PreviewEntryListener,
    ),
)

@Preview(showBackground = true)
@Composable
private fun PreviewWebAppsScreen() {
    WebAppsScreen(
        uiState = WebAppsScreenUiState(
            isLoading = false,
            entries = previewEntries,
            deleteConfirmDialog = null,
            renameDialog = null,
        ),
        onBack = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewWebAppsScreenEmpty() {
    WebAppsScreen(
        uiState = WebAppsScreenUiState(
            isLoading = false,
            entries = listOf(),
            deleteConfirmDialog = null,
            renameDialog = null,
        ),
        onBack = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewWebAppsScreenDeleteConfirm() {
    WebAppsScreen(
        uiState = WebAppsScreenUiState(
            isLoading = false,
            entries = previewEntries,
            deleteConfirmDialog = WebAppsScreenUiState.DeleteConfirmDialog(
                title = "Example",
                listener = PreviewDeleteConfirmDialogListener,
            ),
            renameDialog = null,
        ),
        onBack = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewWebAppsScreenRename() {
    WebAppsScreen(
        uiState = WebAppsScreenUiState(
            isLoading = false,
            entries = previewEntries,
            deleteConfirmDialog = null,
            renameDialog = WebAppsScreenUiState.RenameDialog(
                currentTitle = "Example",
                listener = PreviewRenameDialogListener,
            ),
        ),
        onBack = {},
    )
}

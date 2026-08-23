package net.matsudamper.browser.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.history.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    uiState: HistoryScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("閲覧履歴") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    TextButton(onClick = uiState.callbacks::onClickDeleteAll) {
                        Text("全削除")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = uiState.callbacks::onSearchQueryChange,
                label = { Text("タイトルやURLで検索") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.entries, key = { it.id }) { entry ->
                    HistoryItem(
                        entry = entry,
                        onClick = { uiState.callbacks.onClickEntry(entry.url) },
                        onDelete = { uiState.callbacks.onDeleteEntry(entry.id) },
                    )
                }
            }
        }
    }

    if (uiState.showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::onDismissDeleteAllDialog,
            title = { Text("確認") },
            text = { Text("すべての閲覧履歴を削除しますか？") },
            confirmButton = {
                TextButton(onClick = uiState.callbacks::onConfirmDeleteAll) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = uiState.callbacks::onDismissDeleteAllDialog) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun HistoryItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val displayUrl = HistoryUrlFormat.forDisplay(entry.url)

    ListItem(
        headlineContent = {
            Text(
                text = entry.title.ifBlank { displayUrl },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (entry.title.isNotBlank()) {
                    Text(
                        text = displayUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = dateFormat.format(Date(entry.visitedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "削除",
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val previewHistoryCallbacks = object : HistoryScreenUiState.Callbacks {
    override fun onSearchQueryChange(query: String) = Unit
    override fun onClickEntry(url: String) = Unit
    override fun onDeleteEntry(id: Long) = Unit
    override fun onClickDeleteAll() = Unit
    override fun onConfirmDeleteAll() = Unit
    override fun onDismissDeleteAllDialog() = Unit
}

@Preview(name = "閲覧履歴", showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    MaterialTheme {
        HistoryScreen(
            uiState = HistoryScreenUiState(
                callbacks = previewHistoryCallbacks,
                searchQuery = "",
                entries = listOf(
                    HistoryEntry(
                        id = 1,
                        url = "https://example.com/articles/sample",
                        title = "サンプルページ",
                        visitedAt = 1_700_000_000_000L,
                    ),
                    HistoryEntry(
                        id = 2,
                        url = "http://legacy.example.com/page",
                        title = "",
                        visitedAt = 1_700_000_100_000L,
                    ),
                ),
                showDeleteAllDialog = false,
            ),
            onBack = {},
        )
    }
}

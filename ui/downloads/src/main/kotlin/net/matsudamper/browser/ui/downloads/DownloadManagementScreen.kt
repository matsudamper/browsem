package net.matsudamper.browser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagementScreen(
    uiState: DownloadManagementScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("ダウンロード管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = uiState.callbacks.onOpenDownloadsFolder) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "ダウンロードフォルダを開く",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val loadingState = uiState.loadingState) {
            is DownloadManagementScreenUiState.LoadingState.Loading -> {
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is DownloadManagementScreenUiState.LoadingState.Loaded -> {
                if (loadingState.downloads.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("ダウンロード履歴はありません。")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize(),
                    ) {
                        items(
                            items = loadingState.downloads,
                            key = { it.id },
                        ) { item ->
                            DownloadItemRow(
                                item = item,
                                onCancel = { uiState.callbacks.onCancel(item.id) },
                                onOpenFile = { fileUri -> uiState.callbacks.onOpenFile(fileUri) },
                                onResume = { uiState.callbacks.onResume(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: DownloadManagementScreenUiState.DownloadItem,
    onCancel: () -> Unit,
    onOpenFile: (String) -> Unit,
    onResume: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            when (val status = item.status) {
                is DownloadManagementScreenUiState.DownloadStatus.InProgress -> {
                    TextButton(onClick = onCancel) {
                        Text("キャンセル")
                    }
                }

                is DownloadManagementScreenUiState.DownloadStatus.Completed -> {
                    TextButton(onClick = { onOpenFile(status.fileUri) }) {
                        Text("開く")
                    }
                }

                is DownloadManagementScreenUiState.DownloadStatus.Failed -> {
                    if (status.canResume) {
                        TextButton(onClick = onResume) {
                            Text("再開")
                        }
                    } else {
                        Text(
                            text = "失敗",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                is DownloadManagementScreenUiState.DownloadStatus.Cancelled -> {
                    Text(
                        text = "キャンセル",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when (val status = item.status) {
            is DownloadManagementScreenUiState.DownloadStatus.InProgress -> {
                val sizeText = buildSizeText(status.totalRead, status.contentLength)
                Text(
                    text = sizeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.isIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { status.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }

            is DownloadManagementScreenUiState.DownloadStatus.Completed -> {
                Text(
                    text = "完了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DownloadManagementScreenUiState.DownloadStatus.Failed -> {
                if (!status.canResume) {
                    Text(
                        text = "再試行するには再度ダウンロードしてください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is DownloadManagementScreenUiState.DownloadStatus.Cancelled -> Unit
        }
        Text(
            text = dateFormat.format(Date(item.enqueuedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildSizeText(totalRead: Long, contentLength: Long): String {
    return if (contentLength > 0) {
        "${formatBytes(totalRead)} / ${formatBytes(contentLength)}"
    } else {
        formatBytes(totalRead)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Preview(name = "失敗・再開可能")
@Composable
private fun PreviewFailedCanResume() {
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "example.zip",
                status = DownloadManagementScreenUiState.DownloadStatus.Failed(canResume = true),
                enqueuedAt = 0L,
            ),
            onCancel = {},
            onOpenFile = {},
            onResume = {},
        )
    }
}

@Preview(name = "失敗・再開不可")
@Composable
private fun PreviewFailedCannotResume() {
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "example.zip",
                status = DownloadManagementScreenUiState.DownloadStatus.Failed(canResume = false),
                enqueuedAt = 0L,
            ),
            onCancel = {},
            onOpenFile = {},
            onResume = {},
        )
    }
}

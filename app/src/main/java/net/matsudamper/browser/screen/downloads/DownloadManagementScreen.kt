package net.matsudamper.browser.screen.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadManagementScreen(
    viewModel: DownloadManagementScreenViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ダウンロード管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    // ダウンロードフォルダを開くボタン
                    IconButton(onClick = uiState.callbacks.onOpenDownloadsFolder) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder_open_24dp),
                            contentDescription = "ダウンロードフォルダを開く",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (uiState.downloads.isEmpty()) {
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
                    items = uiState.downloads,
                    key = { it.id },
                ) { item ->
                    DownloadItemRow(
                        item = item,
                        onCancel = { uiState.callbacks.onCancel(item.id) },
                        onOpenFile = { fileUri -> uiState.callbacks.onOpenFile(fileUri) },
                    )
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
                    Text(
                        text = "失敗",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                val sizeText = DownloadWorker.buildSizeText(status.totalRead, status.contentLength)
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
            is DownloadManagementScreenUiState.DownloadStatus.Failed -> Unit
            is DownloadManagementScreenUiState.DownloadStatus.Cancelled -> Unit
        }
        // ダウンロード開始時刻（完了後も常に表示）
        Text(
            text = dateFormat.format(Date(item.enqueuedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

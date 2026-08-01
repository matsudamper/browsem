package net.matsudamper.browser.ui.downloads

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagementScreen(
    uiState: DownloadManagementScreenUiState,
    highlightItemId: UUID?,
    onBack: () -> Unit,
    onHighlightComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
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
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.downloads.isEmpty()) {
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
            var activeHighlightId by remember { mutableStateOf<UUID?>(null) }
            LaunchedEffect(highlightItemId, uiState.downloads) {
                val targetId = highlightItemId ?: return@LaunchedEffect
                val index = uiState.downloads.indexOfFirst { it.id == targetId }
                if (index < 0) return@LaunchedEffect
                listState.animateScrollToItem(index)
                activeHighlightId = targetId
            }
            LazyColumn(
                state = listState,
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
                        onPause = { uiState.callbacks.onPause(item.id) },
                        onOpenFile = { fileUri -> uiState.callbacks.onOpenFile(fileUri) },
                        onResume = { uiState.callbacks.onResume(item.id) },
                        onOpenOriginPage = { url -> uiState.callbacks.onOpenOriginPage(url) },
                        loadPreview = uiState.callbacks.loadPreview,
                        isHighlighted = item.id == activeHighlightId,
                        onHighlightFinished = {
                            activeHighlightId = null
                            onHighlightComplete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadItemRow(
    item: DownloadManagementScreenUiState.DownloadItem,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onOpenFile: (String) -> Unit,
    onResume: () -> Unit,
    onOpenOriginPage: (url: String) -> Unit,
    loadPreview: suspend (fileUri: String) -> DownloadManagementScreenUiState.Preview?,
    isHighlighted: Boolean,
    onHighlightFinished: () -> Unit,
) {
    val currentLoadPreview by rememberUpdatedState(loadPreview)
    val currentOnHighlightFinished by rememberUpdatedState(onHighlightFinished)
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }
    val highlightAlpha = remember { Animatable(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    LaunchedEffect(isHighlighted) {
        if (!isHighlighted) {
            highlightAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        delay(200)
        var activePress: PressInteraction.Press? = null
        try {
            repeat(2) {
                val press = PressInteraction.Press(Offset.Zero)
                activePress = press
                interactionSource.emit(press)
                highlightAlpha.animateTo(1f, tween(200))
                interactionSource.emit(PressInteraction.Release(press))
                activePress = null
                highlightAlpha.animateTo(0f, tween(200))
                if (it == 0) delay(100)
            }
        } finally {
            withContext(NonCancellable) {
                activePress?.let { interactionSource.emit(PressInteraction.Cancel(it)) }
                highlightAlpha.snapTo(0f)
            }
        }
        currentOnHighlightFinished()
    }
    Box {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("開始ページを開く") },
                enabled = item.originPageUrl != null,
                onClick = {
                    menuExpanded = false
                    item.originPageUrl?.let { onOpenOriginPage(it) }
                },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(highlightColor.copy(alpha = highlightColor.alpha * highlightAlpha.value))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onLongClick = { menuExpanded = true },
                    onClick = {
                        val status = item.status
                        if (status is DownloadManagementScreenUiState.DownloadStatus.Completed) {
                            onOpenFile(status.fileUri)
                        }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val completedStatus = item.status as? DownloadManagementScreenUiState.DownloadStatus.Completed
            if (completedStatus != null) {
                var preview by remember(completedStatus.fileUri) {
                    mutableStateOf<DownloadManagementScreenUiState.Preview?>(null)
                }
                var loaded by remember(completedStatus.fileUri) { mutableStateOf(false) }
                LaunchedEffect(completedStatus.fileUri) {
                    preview = currentLoadPreview(completedStatus.fileUri)
                    loaded = true
                }
                val currentPreview = preview
                if (currentPreview != null || !loaded) {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (currentPreview != null) {
                            Image(
                                bitmap = currentPreview.image,
                                contentDescription = null,
                                // サムネイルは領域いっぱいに、アプリアイコンは全体が欠けないように表示する
                                contentScale = when (currentPreview) {
                                    is DownloadManagementScreenUiState.Preview.Thumbnail -> ContentScale.Crop
                                    is DownloadManagementScreenUiState.Preview.AppIcon -> ContentScale.Fit
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    when (val status = item.status) {
                        is DownloadManagementScreenUiState.DownloadStatus.InProgress -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DownloadIconButton(
                                    iconRes = R.drawable.ic_pause,
                                    contentDescription = "一時停止",
                                    onClick = onPause,
                                )
                                DownloadIconButton(
                                    iconRes = R.drawable.ic_close,
                                    contentDescription = "キャンセル",
                                    onClick = onCancel,
                                )
                            }
                        }

                        is DownloadManagementScreenUiState.DownloadStatus.Paused -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DownloadIconButton(
                                    iconRes = R.drawable.ic_play_arrow,
                                    contentDescription = "再開",
                                    onClick = onResume,
                                )
                                DownloadIconButton(
                                    iconRes = R.drawable.ic_close,
                                    contentDescription = "キャンセル",
                                    onClick = onCancel,
                                )
                            }
                        }

                        is DownloadManagementScreenUiState.DownloadStatus.Completed -> Unit

                        is DownloadManagementScreenUiState.DownloadStatus.Failed -> {
                            if (status.canResume) {
                                DownloadIconButton(
                                    iconRes = R.drawable.ic_play_arrow,
                                    contentDescription = "再開",
                                    onClick = onResume,
                                )
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

                    is DownloadManagementScreenUiState.DownloadStatus.Paused -> {
                        val sizeText = buildSizeText(status.totalRead, status.contentLength)
                        Text(
                            text = "一時停止中 $sizeText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { status.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
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
    }
}

@Composable
private fun DownloadIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Preview(name = "ダウンロード中")
@Composable
private fun PreviewInProgress() {
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "example.zip",
                status = DownloadManagementScreenUiState.DownloadStatus.InProgress(
                    progress = 40,
                    totalRead = 40L * 1024 * 1024,
                    contentLength = 100L * 1024 * 1024,
                    isIndeterminate = false,
                ),
                enqueuedAt = 0L,
                originPageUrl = "https://example.com/page",
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { null },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
}

@Preview(name = "一時停止中")
@Composable
private fun PreviewPaused() {
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "example.zip",
                status = DownloadManagementScreenUiState.DownloadStatus.Paused(
                    progress = 40,
                    totalRead = 40L * 1024 * 1024,
                    contentLength = 100L * 1024 * 1024,
                ),
                enqueuedAt = 0L,
                originPageUrl = "https://example.com/page",
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { null },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
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
                originPageUrl = "https://example.com/page",
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { null },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
}

@Preview(name = "完了・サムネイルあり")
@Composable
private fun PreviewCompletedWithThumbnail() {
    val thumbnail = remember {
        ImageBitmap(width = 64, height = 64).also { bitmap ->
            Canvas(bitmap).drawRect(
                rect = Rect(0f, 0f, 64f, 64f),
                paint = Paint().apply { color = Color(0xFF81C784) },
            )
        }
    }
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "photo.png",
                status = DownloadManagementScreenUiState.DownloadStatus.Completed(
                    fileUri = "content://media/external/downloads/1",
                ),
                enqueuedAt = 0L,
                originPageUrl = "https://example.com/page",
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { DownloadManagementScreenUiState.Preview.Thumbnail(thumbnail) },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
}

@Preview(name = "完了・APKアイコンあり")
@Composable
private fun PreviewCompletedWithAppIcon() {
    // APK から取り出したアプリアイコンを模した、余白のある正方形の画像
    val icon = remember {
        ImageBitmap(width = 64, height = 64).also { bitmap ->
            Canvas(bitmap).apply {
                drawRect(
                    rect = Rect(0f, 0f, 64f, 64f),
                    paint = Paint().apply { color = Color(0xFF3DDC84) },
                )
                drawCircle(
                    center = Offset(32f, 32f),
                    radius = 18f,
                    paint = Paint().apply { color = Color.White },
                )
            }
        }
    }
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "sample-app.apk",
                status = DownloadManagementScreenUiState.DownloadStatus.Completed(
                    fileUri = "content://media/external/downloads/4",
                ),
                enqueuedAt = 0L,
                originPageUrl = "https://example.com/page",
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { DownloadManagementScreenUiState.Preview.AppIcon(icon) },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
}

@Preview(name = "完了・サムネイルなし")
@Composable
private fun PreviewCompletedWithoutThumbnail() {
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "example.zip",
                status = DownloadManagementScreenUiState.DownloadStatus.Completed(
                    fileUri = "content://media/external/downloads/2",
                ),
                enqueuedAt = 0L,
                originPageUrl = null,
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { null },
            isHighlighted = false,
            onHighlightFinished = {},
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
                originPageUrl = null,
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { null },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
}

@Preview(name = "長いファイル名・2行表示")
@Composable
private fun PreviewLongFileName() {
    MaterialTheme {
        DownloadItemRow(
            item = DownloadManagementScreenUiState.DownloadItem(
                id = UUID.randomUUID(),
                fileName = "very_long_file_name_that_should_wrap_to_two_lines_example_document.pdf",
                status = DownloadManagementScreenUiState.DownloadStatus.Completed(
                    fileUri = "content://media/external/downloads/3",
                ),
                enqueuedAt = 0L,
                originPageUrl = "https://example.com/page",
            ),
            onCancel = {},
            onPause = {},
            onOpenFile = {},
            onResume = {},
            onOpenOriginPage = {},
            loadPreview = { null },
            isHighlighted = false,
            onHighlightFinished = {},
        )
    }
}

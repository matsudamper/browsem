package net.matsudamper.browser.ui.settings.crash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.matsudamper.browser.ui.common.ThemeSurfaceStatusBarAppearanceEffect
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface CrashLogsScreenTestTags {
    val id: String

    val testTag get() = "${CrashLogsScreenTestTags::class.java.name}#$id"

    data object Root : CrashLogsScreenTestTags {
        override val id = "root"
    }

    data class Entry(val crashLogId: Long) : CrashLogsScreenTestTags {
        override val id = "entry_$crashLogId"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrashLogsScreen(
    uiState: CrashLogsScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ThemeSurfaceStatusBarAppearanceEffect()
    Scaffold(
        modifier = modifier.testTag(CrashLogsScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text("クラッシュログ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    if (uiState.entries.isNotEmpty()) {
                        TextButton(onClick = uiState.callbacks::onClickDeleteAll) {
                            Text("全削除")
                        }
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
                    text = "保存されたクラッシュログはありません",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(16.dp),
                )
            }

            else -> {
                val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
                LazyColumn(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                ) {
                    items(uiState.entries, key = { it.id }) { entry ->
                        CrashLogSummaryListItem(
                            entry = entry,
                            dateFormat = dateFormat,
                        )
                    }
                }
            }
        }
    }

    if (uiState.showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::onDismissDeleteAllDialog,
            title = { Text("確認") },
            text = { Text("すべてのクラッシュログを削除しますか？") },
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
private fun CrashLogSummaryListItem(
    entry: CrashLogsScreenUiState.EntryItem,
    dateFormat: SimpleDateFormat,
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
            Text(
                text = dateFormat.format(Date(entry.occurredAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .testTag(CrashLogsScreenTestTags.Entry(entry.id).testTag)
            .clickable(onClick = entry.listener::onClick),
    )
}

private val previewCallbacks = object : CrashLogsScreenUiState.Callbacks {
    override fun onClickDeleteAll() = Unit
    override fun onConfirmDeleteAll() = Unit
    override fun onDismissDeleteAllDialog() = Unit
}

@Preview(showBackground = true)
@Composable
private fun PreviewCrashLogsScreen() {
    CrashLogsScreen(
        uiState = CrashLogsScreenUiState(
            callbacks = previewCallbacks,
            isLoading = false,
            entries = listOf(
                CrashLogsScreenUiState.EntryItem(
                    id = 1,
                    occurredAt = 1_700_000_000_000,
                    title = "java.lang.RuntimeException: Something went wrong in the browser",
                    listener = PreviewCrashLogEntryListener,
                ),
            ),
            showDeleteAllDialog = false,
        ),
        onBack = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewCrashLogsScreenEmpty() {
    CrashLogsScreen(
        uiState = CrashLogsScreenUiState(
            callbacks = previewCallbacks,
            isLoading = false,
            entries = emptyList(),
            showDeleteAllDialog = false,
        ),
        onBack = {},
    )
}

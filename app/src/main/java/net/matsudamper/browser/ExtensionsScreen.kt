package net.matsudamper.browser

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionsScreen(
    runtime: GeckoRuntime,
    onBack: () -> Unit,
) {
    var extensions by remember { mutableStateOf<List<WebExtension>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var uninstallingId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshExtensions() {
        isLoading = true
        runtime.webExtensionController.list().accept(
            { list ->
                extensions = (list ?: emptyList()).sortedBy { extension ->
                    (extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id).lowercase()
                }
                isLoading = false
            },
            { error ->
                errorMessage = error?.message ?: "拡張機能一覧の取得に失敗しました。"
                isLoading = false
            },
        )
    }

    LaunchedEffect(Unit) {
        refreshExtensions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拡張機能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { refreshExtensions() },
                        enabled = uninstallingId == null,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "再読み込み",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (extensions.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("インストール済み拡張機能はありません。")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                items(
                    items = extensions,
                    key = { extension -> extension.id },
                ) { extension ->
                    ExtensionRow(
                        extension = extension,
                        isUninstalling = uninstallingId == extension.id,
                        uninstallEnabled = uninstallingId == null,
                        onUninstall = {
                            uninstallingId = extension.id
                            runtime.webExtensionController.uninstall(extension).accept(
                                {
                                    uninstallingId = null
                                    refreshExtensions()
                                },
                                { error ->
                                    uninstallingId = null
                                    errorMessage =
                                        error?.message ?: "拡張機能のアンインストールに失敗しました。"
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("エラー") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = { errorMessage = null },
                ) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun ExtensionRow(
    extension: WebExtension,
    isUninstalling: Boolean,
    uninstallEnabled: Boolean,
    onUninstall: () -> Unit,
) {
    val displayName = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id
    val version = extension.metaData.version

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "ID: ${extension.id}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Version: $version",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onUninstall,
            enabled = uninstallEnabled,
        ) {
            Text(if (isUninstalling) "削除中..." else "アンインストール")
        }
    }
}

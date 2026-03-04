package net.matsudamper.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.WebExtension

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionsScreen(
    viewModel: ExtensionsScreenViewModel,
    onBack: () -> Unit,
) {
    val state = rememberExtensionsScreenState(viewModel.runtime)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拡張機能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { state.refreshExtensions() },
                        enabled = state.uninstallingId == null,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh_24dp),
                            contentDescription = "再読み込み",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.extensions.isEmpty()) {
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
                    items = state.extensions,
                    key = { extension -> extension.id },
                ) { extension ->
                    ExtensionRow(
                        extension = extension,
                        isUninstalling = state.uninstallingId == extension.id,
                        uninstallEnabled = state.uninstallingId == null,
                        onOpenSettings = {
                            state.openExtensionSettings(
                                extension = extension,
                                onOpenExtensionSettings = viewModel::onOpenExtensionSettings,
                            )
                        },
                        onUninstall = { state.uninstallExtension(extension) },
                    )
                }
            }
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = state::dismissError,
            title = { Text("エラー") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = state::dismissError) {
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
    onOpenSettings: () -> Unit,
    onUninstall: () -> Unit,
) {
    val displayName = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id
    val version = extension.metaData.version

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                onClick = onOpenSettings,
            )
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
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            onClick = onUninstall,
            enabled = uninstallEnabled,
        ) {
            Text(if (isUninstalling) "削除中..." else "アンインストール")
        }
    }
}

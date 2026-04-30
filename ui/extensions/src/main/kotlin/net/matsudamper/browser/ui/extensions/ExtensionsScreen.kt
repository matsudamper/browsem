package net.matsudamper.browser.ui.extensions

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    uiState: ExtensionsScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
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
                        onClick = uiState.callbacks::refreshExtensions,
                        enabled = uiState.uninstallingId == null,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "再読み込み",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val loadingState = uiState.loadingState) {
            is ExtensionsScreenUiState.LoadingState.Loading -> {
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is ExtensionsScreenUiState.LoadingState.Loaded -> {
                if (loadingState.extensions.isEmpty()) {
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
                            items = loadingState.extensions,
                            key = { it.id },
                        ) { extension ->
                            ExtensionRow(
                                extension = extension,
                                isUninstalling = uiState.uninstallingId == extension.id,
                                uninstallEnabled = uiState.uninstallingId == null,
                                onOpenSettings = { uiState.callbacks.openExtensionSettings(extension.id) },
                                onUninstall = { uiState.callbacks.uninstallExtension(extension.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissError,
            title = { Text("エラー") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = uiState.callbacks::dismissError) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun ExtensionRow(
    extension: ExtensionsScreenUiState.ExtensionUiState,
    isUninstalling: Boolean,
    uninstallEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onUninstall: () -> Unit,
) {
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
                text = extension.displayName,
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
                text = "Version: ${extension.version}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (extension.hasSettingsPage) {
                TextButton(
                    onClick = onOpenSettings,
                    enabled = uninstallEnabled,
                ) {
                    Text("設定")
                }
            }
            TextButton(
                onClick = onUninstall,
                enabled = uninstallEnabled,
            ) {
                Text(if (isUninstalling) "削除中..." else "アンインストール")
            }
        }
    }
}

private val previewCallbacks = object : ExtensionsScreenUiState.Callbacks {
    override fun refreshExtensions() = Unit
    override fun uninstallExtension(extensionId: String) = Unit
    override fun openExtensionSettings(extensionId: String) = Unit
    override fun dismissError() = Unit
}

/** 設定ページあり・なしの両方の拡張機能が列挙される状態 */
@Preview(showBackground = true)
@Composable
private fun PreviewExtensionsScreenLoaded() {
    ExtensionsScreen(
        uiState = ExtensionsScreenUiState(
            callbacks = previewCallbacks,
            loadingState = ExtensionsScreenUiState.LoadingState.Loaded(
                extensions = listOf(
                    ExtensionsScreenUiState.ExtensionUiState(
                        id = "ublock@example.com",
                        displayName = "uBlock Origin",
                        version = "1.54.0",
                        hasSettingsPage = true,
                    ),
                    ExtensionsScreenUiState.ExtensionUiState(
                        id = "no-settings@example.com",
                        displayName = "設定ページなしの拡張機能",
                        version = "0.1.0",
                        hasSettingsPage = false,
                    ),
                ),
            ),
            errorMessage = null,
            uninstallingId = null,
        ),
        onBack = {},
    )
}

/** アンインストール中の拡張機能がある状態 */
@Preview(showBackground = true)
@Composable
private fun PreviewExtensionsScreenUninstalling() {
    ExtensionsScreen(
        uiState = ExtensionsScreenUiState(
            callbacks = previewCallbacks,
            loadingState = ExtensionsScreenUiState.LoadingState.Loaded(
                extensions = listOf(
                    ExtensionsScreenUiState.ExtensionUiState(
                        id = "ublock@example.com",
                        displayName = "uBlock Origin",
                        version = "1.54.0",
                        hasSettingsPage = true,
                    ),
                ),
            ),
            errorMessage = null,
            uninstallingId = "ublock@example.com",
        ),
        onBack = {},
    )
}

/** ロード中の状態 */
@Preview(showBackground = true)
@Composable
private fun PreviewExtensionsScreenLoading() {
    ExtensionsScreen(
        uiState = ExtensionsScreenUiState(
            callbacks = previewCallbacks,
            loadingState = ExtensionsScreenUiState.LoadingState.Loading,
            errorMessage = null,
            uninstallingId = null,
        ),
        onBack = {},
    )
}

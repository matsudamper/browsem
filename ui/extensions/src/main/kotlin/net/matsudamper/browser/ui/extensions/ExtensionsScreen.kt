package net.matsudamper.browser.ui.extensions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface ExtensionsScreenTestTags {
    val id: String

    val testTag get() = "${ExtensionsScreenTestTags::class.java.name}#$id"

    object InstallFromFileButton : ExtensionsScreenTestTags { override val id = "install_from_file_button" }
    object AllExtensionsSwitch : ExtensionsScreenTestTags { override val id = "all_extensions_switch" }
    object SystemSectionHeader : ExtensionsScreenTestTags { override val id = "system_section_header" }
}

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
                    if (uiState.isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = uiState.callbacks::installExtensionFromFile,
                            enabled = uiState.uninstallingId == null &&
                                uiState.togglingId == null &&
                                !uiState.isTogglingAll,
                            modifier = Modifier.testTag(ExtensionsScreenTestTags.InstallFromFileButton.testTag),
                        ) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.ic_add_24dp),
                                contentDescription = "ファイルからインストール",
                            )
                        }
                    }
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
                val userExtensions = loadingState.extensions.filterNot { it.isBuiltIn }
                val systemExtensions = loadingState.extensions.filter { it.isBuiltIn }
                val individualToggleEnabled = uiState.togglingId == null &&
                    uiState.uninstallingId == null &&
                    !uiState.isTogglingAll
                val masterToggleEnabled = uiState.togglingId == null &&
                    uiState.uninstallingId == null &&
                    !uiState.isTogglingAll

                LazyColumn(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                ) {
                    item(key = "all_extensions_switch") {
                        AllExtensionsSwitchRow(
                            areExtensionsEnabled = loadingState.areExtensionsEnabled,
                            isTogglingAll = uiState.isTogglingAll,
                            enabled = masterToggleEnabled,
                            onToggle = uiState.callbacks::setAllExtensionsEnabled,
                        )
                    }

                    if (userExtensions.isEmpty() && systemExtensions.isEmpty()) {
                        item(key = "empty_message") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("インストール済み拡張機能はありません。")
                            }
                        }
                    } else {
                        items(
                            items = userExtensions,
                            key = { it.id },
                        ) { extension ->
                            ExtensionRow(
                                extension = extension,
                                isUninstalling = uiState.uninstallingId == extension.id,
                                uninstallEnabled = individualToggleEnabled,
                                isToggling = uiState.togglingId == extension.id,
                                toggleEnabled = individualToggleEnabled,
                                onOpenSettings = { uiState.callbacks.openExtensionSettings(extension.id) },
                                onUninstall = { uiState.callbacks.uninstallExtension(extension.id) },
                                onToggle = { enabled -> uiState.callbacks.setExtensionEnabled(extension.id, enabled) },
                            )
                        }

                        if (systemExtensions.isNotEmpty()) {
                            item(key = "system_section_header") {
                                ExtensionSectionHeader(
                                    title = "システム",
                                    modifier = Modifier.testTag(ExtensionsScreenTestTags.SystemSectionHeader.testTag),
                                )
                            }
                            items(
                                items = systemExtensions,
                                key = { it.id },
                            ) { extension ->
                                ExtensionRow(
                                    extension = extension,
                                    isUninstalling = uiState.uninstallingId == extension.id,
                                    uninstallEnabled = individualToggleEnabled,
                                    isToggling = uiState.togglingId == extension.id,
                                    toggleEnabled = individualToggleEnabled,
                                    onOpenSettings = { uiState.callbacks.openExtensionSettings(extension.id) },
                                    onUninstall = { uiState.callbacks.uninstallExtension(extension.id) },
                                    onToggle = { enabled ->
                                        uiState.callbacks.setExtensionEnabled(extension.id, enabled)
                                    },
                                )
                            }
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
private fun AllExtensionsSwitchRow(
    areExtensionsEnabled: Boolean,
    isTogglingAll: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
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
                text = "拡張機能を有効にする",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "すべての拡張機能をまとめて有効/無効にします",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isTogglingAll) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Switch(
                checked = areExtensionsEnabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier
                    .testTag(ExtensionsScreenTestTags.AllExtensionsSwitch.testTag)
                    .semantics {
                        contentDescription = "すべての拡張機能の有効/無効"
                    },
            )
        }
    }
}

@Composable
private fun ExtensionSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private val previewCallbacks = object : ExtensionsScreenUiState.Callbacks {
    override fun refreshExtensions() = Unit
    override fun installExtensionFromFile() = Unit
    override fun uninstallExtension(extensionId: String) = Unit
    override fun openExtensionSettings(extensionId: String) = Unit
    override fun setExtensionEnabled(extensionId: String, enabled: Boolean) = Unit
    override fun setAllExtensionsEnabled(enabled: Boolean) = Unit
    override fun dismissError() = Unit
}

@Preview(showBackground = true)
@Composable
private fun ExtensionsScreenLoadedPreview() {
    MaterialTheme {
        ExtensionsScreen(
            uiState = ExtensionsScreenUiState(
                callbacks = previewCallbacks,
                loadingState = ExtensionsScreenUiState.LoadingState.Loaded(
                    extensions = listOf(
                        ExtensionsScreenUiState.ExtensionUiState(
                            id = "ublock-origin@raymondhill.net",
                            displayName = "uBlock Origin",
                            version = "1.57.2",
                            hasSettingsPage = true,
                            isEnabled = true,
                            isBuiltIn = false,
                        ),
                        ExtensionsScreenUiState.ExtensionUiState(
                            id = "some-disabled-extension@example.com",
                            displayName = "無効な拡張機能",
                            version = "0.9.0",
                            hasSettingsPage = false,
                            isEnabled = false,
                            isBuiltIn = false,
                        ),
                        ExtensionsScreenUiState.ExtensionUiState(
                            id = "readability@built-in",
                            displayName = "Readability (ビルトイン)",
                            version = "1.0.0",
                            hasSettingsPage = false,
                            isEnabled = true,
                            isBuiltIn = true,
                        ),
                    ),
                    areExtensionsEnabled = false,
                ),
                errorMessage = null,
                uninstallingId = null,
                togglingId = null,
                isTogglingAll = false,
                isInstalling = false,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ExtensionsScreenTogglingPreview() {
    MaterialTheme {
        ExtensionsScreen(
            uiState = ExtensionsScreenUiState(
                callbacks = previewCallbacks,
                loadingState = ExtensionsScreenUiState.LoadingState.Loaded(
                    extensions = listOf(
                        ExtensionsScreenUiState.ExtensionUiState(
                            id = "ublock-origin@raymondhill.net",
                            displayName = "uBlock Origin",
                            version = "1.57.2",
                            hasSettingsPage = true,
                            isEnabled = true,
                            isBuiltIn = false,
                        ),
                    ),
                    areExtensionsEnabled = true,
                ),
                errorMessage = null,
                uninstallingId = null,
                togglingId = "ublock-origin@raymondhill.net",
                isTogglingAll = false,
                isInstalling = false,
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ExtensionsScreenInstallingPreview() {
    MaterialTheme {
        ExtensionsScreen(
            uiState = ExtensionsScreenUiState(
                callbacks = previewCallbacks,
                loadingState = ExtensionsScreenUiState.LoadingState.Loaded(
                    extensions = listOf(
                        ExtensionsScreenUiState.ExtensionUiState(
                            id = "ublock-origin@raymondhill.net",
                            displayName = "uBlock Origin",
                            version = "1.57.2",
                            hasSettingsPage = true,
                            isEnabled = true,
                            isBuiltIn = false,
                        ),
                    ),
                    areExtensionsEnabled = true,
                ),
                errorMessage = null,
                uninstallingId = null,
                togglingId = null,
                isTogglingAll = false,
                isInstalling = true,
            ),
            onBack = {},
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtensionRow(
    extension: ExtensionsScreenUiState.ExtensionUiState,
    isUninstalling: Boolean,
    uninstallEnabled: Boolean,
    isToggling: Boolean,
    toggleEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (!extension.isBuiltIn) {
                DropdownMenuItem(
                    text = { Text(if (isUninstalling) "削除中..." else "アンインストール") },
                    enabled = uninstallEnabled,
                    onClick = {
                        menuExpanded = false
                        onUninstall()
                    },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpenSettings,
                    onLongClick = { menuExpanded = true },
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
            Switch(
                checked = extension.isEnabled,
                onCheckedChange = { onToggle(it) },
                enabled = toggleEnabled && !isToggling,
                modifier = Modifier.semantics {
                    contentDescription = "${extension.displayName} の有効/無効"
                },
            )
        }
    }
}

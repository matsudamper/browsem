package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface SiteFormInputPathScreenTestTags {
    val id: String
    val testTag get() = "${SiteFormInputPathScreenTestTags::class.java.name}#$id"

    data object Root : SiteFormInputPathScreenTestTags { override val id = "root" }

    data class FieldEntry(val fieldKey: String) : SiteFormInputPathScreenTestTags {
        override val id = "field_$fieldKey"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteFormInputPathScreen(
    uiState: SiteFormInputPathScreenUiState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(SiteFormInputPathScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text(uiState.displayPath) },
                navigationIcon = {
                    IconButton(onClick = uiState.callbacks::navigateBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            item {
                SettingSection(title = "このパス") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = uiState.pathEnabled,
                                role = Role.Switch,
                                onValueChange = uiState.callbacks::setPathEnabled,
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "保存とサジェスト",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "このパスでのフォーム入力の自動保存と候補表示",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.pathEnabled,
                            onCheckedChange = null,
                        )
                    }
                }
            }

            item {
                Text(
                    text = "フィールド",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (uiState.fields.isEmpty()) {
                item {
                    Text(
                        text = "保存されたフィールドはありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(uiState.fields, key = { it.fieldKey }) { field ->
                    SiteFormInputFieldListItem(
                        field = field,
                        onToggle = { enabled ->
                            uiState.callbacks.setFieldEnabled(field.fieldKey, enabled)
                        },
                        onDelete = { uiState.callbacks.requestDeleteField(field.fieldKey) },
                        modifier = Modifier.testTag(
                            SiteFormInputPathScreenTestTags.FieldEntry(field.fieldKey).testTag,
                        ),
                    )
                }
            }

            item {
                TextButton(
                    onClick = uiState.callbacks::requestDeletePath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = "このパスの保存データをすべて削除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    val deleteFieldKey = uiState.deleteFieldConfirm
    if (deleteFieldKey != null) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissDeleteFieldConfirm,
            title = { Text("フィールドのデータを削除") },
            text = {
                Text("「$deleteFieldKey」の保存データを削除しますか？この操作は取り消せません。")
            },
            confirmButton = {
                TextButton(onClick = uiState.callbacks::confirmDeleteField) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = uiState.callbacks::dismissDeleteFieldConfirm) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (uiState.deletePathConfirm) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissDeletePathConfirm,
            title = { Text("パスのデータを削除") },
            text = {
                Text(
                    "「${uiState.displayPath}」の保存データをすべて削除しますか？この操作は取り消せません。",
                )
            },
            confirmButton = {
                TextButton(onClick = uiState.callbacks::confirmDeletePath) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = uiState.callbacks::dismissDeletePathConfirm) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun SiteFormInputFieldListItem(
    field: SiteFormInputPathScreenUiState.FieldEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(field.fieldKey) },
        supportingContent = {
            if (field.previewText.isNotBlank()) {
                Text(
                    text = field.previewText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
                Switch(
                    checked = field.enabled,
                    onCheckedChange = onToggle,
                )
            }
        },
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun SiteFormInputPathScreenPreview() {
    MaterialTheme {
        SiteFormInputPathScreen(
            uiState = SiteFormInputPathScreenUiState(
                callbacks = object : SiteFormInputPathScreenUiState.Callbacks {
                    override fun navigateBack() = Unit
                    override fun setPathEnabled(enabled: Boolean) = Unit
                    override fun setFieldEnabled(fieldKey: String, enabled: Boolean) = Unit
                    override fun requestDeleteField(fieldKey: String) = Unit
                    override fun confirmDeleteField() = Unit
                    override fun dismissDeleteFieldConfirm() = Unit
                    override fun requestDeletePath() = Unit
                    override fun confirmDeletePath() = Unit
                    override fun dismissDeletePathConfirm() = Unit
                },
                displayOrigin = "https://example.com",
                path = "/contact",
                displayPath = "/contact",
                pathEnabled = true,
                fields = listOf(
                    SiteFormInputPathScreenUiState.FieldEntry(
                        fieldKey = "comment",
                        previewText = "以前のコメント / 別のコメント",
                        enabled = true,
                    ),
                    SiteFormInputPathScreenUiState.FieldEntry(
                        fieldKey = "subject",
                        previewText = "お問い合わせ",
                        enabled = false,
                    ),
                ),
                deleteFieldConfirm = null,
                deletePathConfirm = false,
            ),
        )
    }
}

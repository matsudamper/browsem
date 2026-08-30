package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface SiteFormInputFieldScreenTestTags {
    val id: String
    val testTag get() = "${SiteFormInputFieldScreenTestTags::class.java.name}#$id"

    data object Root : SiteFormInputFieldScreenTestTags { override val id = "root" }

    data class ValueEntry(val value: String) : SiteFormInputFieldScreenTestTags {
        override val id = "value_${value.hashCode()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteFormInputFieldScreen(
    uiState: SiteFormInputFieldScreenUiState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(SiteFormInputFieldScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text(uiState.fieldKey) },
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
                Text(
                    text = "${uiState.displayOrigin}${uiState.displayPath}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                Text(
                    text = "保存した値",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (uiState.values.isEmpty()) {
                item {
                    Text(
                        text = "保存された値はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(uiState.values, key = { it }) { value ->
                    SiteFormInputValueListItem(
                        value = value,
                        onDelete = { uiState.callbacks.requestDeleteValue(value) },
                        modifier = Modifier.testTag(
                            SiteFormInputFieldScreenTestTags.ValueEntry(value).testTag,
                        ),
                    )
                }
            }

            item {
                TextButton(
                    onClick = uiState.callbacks::requestDeleteField,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = "このフィールドを削除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    val deleteValue = uiState.deleteValueConfirm
    if (deleteValue != null) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissDeleteValueConfirm,
            title = { Text("値を削除") },
            text = {
                Text("この保存値を削除しますか？この操作は取り消せません。")
            },
            confirmButton = {
                TextButton(onClick = uiState.callbacks::confirmDeleteValue) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = uiState.callbacks::dismissDeleteValueConfirm) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (uiState.deleteFieldConfirm) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissDeleteFieldConfirm,
            title = { Text("フィールドを削除") },
            text = {
                Text(
                    "「${uiState.fieldKey}」を削除しますか？保存した値もすべて削除され、この操作は取り消せません。",
                )
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
}

@Composable
private fun SiteFormInputValueListItem(
    value: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = {
            Text(
                text = value,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            TextButton(onClick = onDelete) {
                Text("削除", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun SiteFormInputFieldScreenPreview() {
    MaterialTheme {
        SiteFormInputFieldScreen(
            uiState = SiteFormInputFieldScreenUiState(
                callbacks = object : SiteFormInputFieldScreenUiState.Callbacks {
                    override fun navigateBack() = Unit
                    override fun requestDeleteValue(value: String) = Unit
                    override fun confirmDeleteValue() = Unit
                    override fun dismissDeleteValueConfirm() = Unit
                    override fun requestDeleteField() = Unit
                    override fun confirmDeleteField() = Unit
                    override fun dismissDeleteFieldConfirm() = Unit
                },
                displayOrigin = "https://example.com",
                path = "/contact",
                displayPath = "/contact",
                fieldKey = "brcNum",
                values = listOf("001", "002", "003"),
                deleteValueConfirm = null,
                deleteFieldConfirm = false,
            ),
        )
    }
}

package net.matsudamper.browser.ui.settings.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

sealed interface SiteFormInputFieldScreenTestTags {
    val id: String
    val testTag get() = "${SiteFormInputFieldScreenTestTags::class.java.name}#$id"

    data object Root : SiteFormInputFieldScreenTestTags {
        override val id = "root"
    }

    data class ValueEntry(val value: String) : SiteFormInputFieldScreenTestTags {
        override val id = "value_${value.hashCode()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteFormInputFieldScreen(
    uiState: SiteFormInputFieldScreenUiState,
    modifier: Modifier = Modifier,
) {
    InputListScreenScaffold(
        modifier = modifier.testTag(SiteFormInputFieldScreenTestTags.Root.testTag),
        onClickBack = { uiState.callbacks.navigateBack() },
        pageTitle = "保存されたid",
        pageSubTitleAction = {
            OutlinedButton(
                onClick = { uiState.callbacks.requestDeleteField() },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("削除")
            }
        },
        pageSubTitle = uiState.fieldKey,
        listTitle = "保存した値",
    ) { paddingValues ->
        if (uiState.values.isEmpty()) {
            Text(
                modifier = Modifier.padding(paddingValues),
                text = "保存された値はありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = paddingValues,
            ) {
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
            title = { Text("idを削除") },
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(
            onClick = onDelete,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("削除")
        }
    }
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
                displayPath = "/contact",
                fieldKey = "brcNum",
                values = listOf("001", "002", "003"),
                deleteValueConfirm = null,
                deleteFieldConfirm = false,
            ),
        )
    }
}

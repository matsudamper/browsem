package net.matsudamper.browser.ui.settings.form

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

sealed interface SiteFormInputPathScreenTestTags {
    val id: String
    val testTag get() = "${SiteFormInputPathScreenTestTags::class.java.name}#$id"

    data object Root : SiteFormInputPathScreenTestTags {
        override val id = "root"
    }

    data class FieldEntry(val fieldKey: String) : SiteFormInputPathScreenTestTags {
        override val id = "field_$fieldKey"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteFormInputPathScreen(
    uiState: SiteFormInputPathScreenUiState,
    modifier: Modifier = Modifier,
) {
    InputListScreenScaffold(
        modifier = modifier.testTag(SiteFormInputPathScreenTestTags.Root.testTag),
        onClickBack = { uiState.callbacks.navigateBack() },
        pageTitle = "保存されたフィールド",
        pageSubTitleAction = {
            OutlinedButton(
                onClick = { uiState.callbacks.requestDeletePath() },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("削除")
            }
        },
        pageSubTitle = uiState.path,
        listTitle = "id一覧",
    ) { paddingValues ->
        if (uiState.fields.isEmpty()) {
            Text(
                modifier = Modifier.padding(paddingValues),
                text = "保存されたフィールドはありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = paddingValues,
            ) {
                items(uiState.fields, key = { it.fieldKey }) { field ->
                    SiteFormInputDeletableListItem(
                        modifier = Modifier.testTag(
                            SiteFormInputPathScreenTestTags.FieldEntry(field.fieldKey).testTag,
                        ),
                        title = field.fieldKey,
                        subTitle = field.previewText,
                        onClick = { uiState.callbacks.openField(field.fieldKey) },
                        onDelete = { uiState.callbacks.requestDeleteField(field.fieldKey) },
                        contentPadding = PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                    )
                }
            }
        }
    }

    val deletePathConfirm = uiState.deletePathConfirm
    if (deletePathConfirm) {
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

    val deleteFieldKey = uiState.deleteFieldConfirm
    if (deleteFieldKey != null) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::dismissDeleteFieldConfirm,
            title = { Text("idを削除") },
            text = {
                Text(
                    "「$deleteFieldKey」を削除しますか？保存した値もすべて削除され、この操作は取り消せません。",
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

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun SiteFormInputPathScreenPreview() {
    MaterialTheme {
        SiteFormInputPathScreen(
            uiState = SiteFormInputPathScreenUiState(
                callbacks = object : SiteFormInputPathScreenUiState.Callbacks {
                    override fun navigateBack() = Unit
                    override fun openField(fieldKey: String) = Unit
                    override fun requestDeleteField(fieldKey: String) = Unit
                    override fun confirmDeleteField() = Unit
                    override fun dismissDeleteFieldConfirm() = Unit
                    override fun requestDeletePath() = Unit
                    override fun confirmDeletePath() = Unit
                    override fun dismissDeletePathConfirm() = Unit
                },
                path = "/contact",
                displayPath = "/contact",
                fields = listOf(
                    SiteFormInputPathScreenUiState.FieldEntry(
                        fieldKey = "comment",
                        previewText = "以前のコメント/別のコメント",
                    ),
                    SiteFormInputPathScreenUiState.FieldEntry(
                        fieldKey = "subject",
                        previewText = "お問い合わせ",
                    ),
                ),
                deletePathConfirm = false,
                deleteFieldConfirm = null,
            ),
        )
    }
}

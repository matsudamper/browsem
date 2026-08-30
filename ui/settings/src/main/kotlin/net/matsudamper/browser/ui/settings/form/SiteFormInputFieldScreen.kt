package net.matsudamper.browser.ui.settings.form

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import net.matsudamper.browser.ui.settings.FormInputDeletableListRow
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
fun SiteFormInputFieldScreen(
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
}

@Composable
private fun SiteFormInputValueListItem(
    value: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormInputDeletableListRow(
        onDelete = onDelete,
        modifier = modifier,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
                },
                displayOrigin = "https://example.com",
                path = "/contact",
                displayPath = "/contact",
                fieldKey = "brcNum",
                values = listOf("001", "002", "003"),
                deleteValueConfirm = null,
            ),
        )
    }
}

package net.matsudamper.browser.ui.settings.address

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

sealed interface AddressesScreenTestTags {
    val id: String

    val testTag get() = "${AddressesScreenTestTags::class.java.name}#$id"

    data object Root : AddressesScreenTestTags { override val id = "root" }
    data object AddButton : AddressesScreenTestTags { override val id = "add_button" }

    data class Entry(val addressId: Long) : AddressesScreenTestTags {
        override val id = "entry_$addressId"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesScreen(
    uiState: AddressesScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(AddressesScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text("住所") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = uiState.callbacks::onClickAdd,
                        modifier = Modifier.testTag(AddressesScreenTestTags.AddButton.testTag),
                    ) {
                        Text("追加")
                    }
                    if (uiState.entries.isNotEmpty()) {
                        TextButton(onClick = uiState.callbacks::onClickDeleteAll) {
                            Text("全削除")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        if (uiState.entries.isEmpty()) {
            Text(
                text = "保存された住所はありません",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                items(uiState.entries, key = { it.id }) { entry ->
                    AddressListItem(entry = entry)
                }
            }
        }
    }

    if (uiState.showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = uiState.callbacks::onDismissDeleteAllDialog,
            title = { Text("確認") },
            text = { Text("すべての住所を削除しますか？") },
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
private fun AddressListItem(
    entry: AddressesScreenUiState.EntryItem,
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (entry.displayDetail.isNotEmpty()) {
                    Text(
                        text = entry.displayDetail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = entry.listener::onDelete) {
                Icon(
                    painter = painterResource(ResourcesR.drawable.ic_delete_24dp),
                    contentDescription = "削除",
                )
            }
        },
        modifier = Modifier
            .testTag(AddressesScreenTestTags.Entry(entry.id).testTag)
            .clickable(onClick = entry.listener::onClick),
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewAddressesScreen() {
    AddressesScreen(
            uiState = AddressesScreenUiState(
                callbacks = object : AddressesScreenUiState.Callbacks {
                    override fun onClickAdd() = Unit
                    override fun onClickDeleteAll() = Unit
                    override fun onConfirmDeleteAll() = Unit
                    override fun onDismissDeleteAllDialog() = Unit
                },
                entries = listOf(
                    AddressesScreenUiState.EntryItem(
                        id = 1,
                        displayName = "山田 太郎",
                        displayDetail = "〒1000001 東京都千代田区千代田1-1",
                        listener = PreviewAddressesEntryListener,
                    ),
                ),
                showDeleteAllDialog = false,
            ),
            onBack = {},
        )
}

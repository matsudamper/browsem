package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import net.matsudamper.browser.data.address.AddressEntity
import net.matsudamper.browser.data.address.displayName
import net.matsudamper.browser.data.address.displayText
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
                    AddressListItem(
                        entry = entry,
                        onClick = { uiState.callbacks.onClickEntry(entry.id) },
                        onDelete = { uiState.callbacks.onDeleteEntry(entry.id) },
                    )
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
    entry: AddressEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.displayName().ifEmpty { "（名前なし）" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                val detail = entry.displayText()
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(ResourcesR.drawable.ic_delete_24dp),
                    contentDescription = "削除",
                )
            }
        },
        modifier = Modifier
            .testTag(AddressesScreenTestTags.Entry(entry.id).testTag)
            .clickable(onClick = onClick),
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewAddressesScreen() {
    AddressesScreen(
            uiState = AddressesScreenUiState(
                callbacks = object : AddressesScreenUiState.Callbacks {
                    override fun onClickAdd() = Unit
                    override fun onClickEntry(id: Long) = Unit
                    override fun onDeleteEntry(id: Long) = Unit
                    override fun onClickDeleteAll() = Unit
                    override fun onConfirmDeleteAll() = Unit
                    override fun onDismissDeleteAllDialog() = Unit
                },
                entries = listOf(
                    AddressEntity(
                        id = 1,
                        familyName = "山田",
                        givenName = "太郎",
                        postalCode = "1000001",
                        addressLevel1 = "東京都",
                        addressLevel2 = "千代田区",
                        streetAddress = "千代田1-1",
                        tel = "0312345678",
                        email = "taro@example.com",
                    ),
                ),
                showDeleteAllDialog = false,
            ),
            onBack = {},
        )
}

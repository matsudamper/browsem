package net.matsudamper.browser.ui.settings.site

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.StatusBarAppearanceEffect

sealed interface SiteSettingsListScreenTestTags {
    val id: String
    val testTag get() = "${SiteSettingsListScreenTestTags::class.java.name}#$id"

    data object Root : SiteSettingsListScreenTestTags {
        override val id = "root"
    }

    data object SearchInput : SiteSettingsListScreenTestTags {
        override val id = "searchInput"
    }

    data class HostRow(val host: String) : SiteSettingsListScreenTestTags {
        override val id = "hostRow:$host"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSettingsListScreen(
    uiState: SiteSettingsListScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusBarAppearanceEffect(MaterialTheme.colorScheme.surface)
    Scaffold(
        modifier = modifier.testTag(SiteSettingsListScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text("サイトの設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = uiState.callbacks::setQuery,
                label = { Text("ドメインを検索") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SiteSettingsListScreenTestTags.SearchInput.testTag),
            )
            Spacer(Modifier.height(8.dp))
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.hosts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (uiState.query.isBlank()) {
                            "サイトごとの設定はありません"
                        } else {
                            "一致するドメインがありません"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        items = uiState.hosts,
                        key = { _, host -> host },
                    ) { index, host ->
                        if (index == uiState.hosts.lastIndex && uiState.hasNextPage) {
                            LaunchedEffect(uiState.query, uiState.hosts.size) {
                                uiState.callbacks.loadNextPage()
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SiteSettingsListScreenTestTags.HostRow(host).testTag)
                                .clickable { uiState.callbacks.openSiteSettings(host) }
                                .padding(horizontal = 4.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = host,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SiteSettingsListScreenPreview() {
    MaterialTheme {
        SiteSettingsListScreen(
            uiState = SiteSettingsListScreenUiState(
                callbacks = object : SiteSettingsListScreenUiState.Callbacks {
                    override fun setQuery(query: String) = Unit

                    override fun loadNextPage() = Unit

                    override fun openSiteSettings(host: String) = Unit
                },
                query = "example",
                hosts = listOf(
                    "example.com",
                    "news.example.jp",
                    "shop.example.net",
                ),
                isLoading = false,
                hasNextPage = false,
            ),
            onBack = {},
        )
    }
}

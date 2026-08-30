package net.matsudamper.browser.ui.settings.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

sealed interface SiteFormInputPathsScreenTestTags {
    val id: String
    val testTag get() = "${SiteFormInputPathsScreenTestTags::class.java.name}#$id"

    data object Root : SiteFormInputPathsScreenTestTags {
        override val id = "root"
    }

    data class PathEntry(val path: String) : SiteFormInputPathsScreenTestTags {
        override val id = "path_${path.replace('/', '_')}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteFormInputPathsScreen(
    uiState: SiteFormInputPathsScreenUiState,
    modifier: Modifier = Modifier,
) {
    InputListScreenScaffold(
        modifier = modifier.testTag(SiteFormInputPathsScreenTestTags.Root.testTag),
        pageTitle = "保存したフォーム入力",
        pageSubTitle = uiState.displayOrigin,
        listTitle = "path一覧",
        onClickBack = uiState.callbacks::navigateBack,
    ) { paddingValues ->
        val listItemPadding = 16.dp
        if (uiState.paths.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    modifier = Modifier
                        .padding(paddingValues),
                    text = "保存されたフォーム入力はありません",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = paddingValues,
            ) {
                items(uiState.paths, key = { it.path }) { entry ->
                    SiteFormInputPathListItem(
                        modifier = Modifier
                            .testTag(SiteFormInputPathsScreenTestTags.PathEntry(entry.path).testTag)
                            .fillMaxWidth(),
                        entry = entry,
                        onOpen = { uiState.callbacks.openPath(entry.path) },
                        contentPadding = PaddingValues(
                            horizontal = listItemPadding,
                            vertical = 8.dp,
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SiteFormInputPathListItem(
    entry: SiteFormInputPathsScreenUiState.PathEntry,
    onOpen: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable {
                onOpen()
            }
            .padding(contentPadding)
    ) {
        Text(
            text = entry.displayPath,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "${entry.fieldCount} 件のフィールド",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    MaterialTheme {
        SiteFormInputPathsScreen(
            uiState = SiteFormInputPathsScreenUiState(
                callbacks = object : SiteFormInputPathsScreenUiState.Callbacks {
                    override fun navigateBack() = Unit
                    override fun openPath(path: String) = Unit
                },
                displayOrigin = "https://example.com",
                paths = listOf(
                    SiteFormInputPathsScreenUiState.PathEntry(
                        path = "/contact",
                        displayPath = "/contact",
                        fieldCount = 2,
                    ),
                    SiteFormInputPathsScreenUiState.PathEntry(
                        path = "",
                        displayPath = "/",
                        fieldCount = 1,
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyPreview() {
    MaterialTheme {
        SiteFormInputPathsScreen(
            uiState = SiteFormInputPathsScreenUiState(
                callbacks = object : SiteFormInputPathsScreenUiState.Callbacks {
                    override fun navigateBack() = Unit
                    override fun openPath(path: String) = Unit
                },
                displayOrigin = "https://example.com",
                paths = listOf(),
            ),
        )
    }
}

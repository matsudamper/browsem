package net.matsudamper.browser.ui.settings.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

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
    Scaffold(
        modifier = modifier.testTag(SiteFormInputPathsScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text("保存したフォーム入力") },
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
        Column(
            modifier = Modifier.padding(
                top = paddingValues.calculateTopPadding(),
            )
        ) {
            val horizontalPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
            )
            val itemHorizontalPadding = 16.dp
            Text(
                modifier = Modifier
                    .padding(horizontalPadding)
                    .padding(horizontal = itemHorizontalPadding, vertical = 8.dp),
                text = uiState.displayOrigin,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier
                    .padding(horizontalPadding)
                    .padding(itemHorizontalPadding),
                text = "path一覧",
            )
            val containerShape = MaterialTheme.shapes.extraLarge
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontalPadding),
                shape = containerShape.copy(
                    bottomEnd = CornerSize(0.dp),
                    bottomStart = CornerSize(0.dp)
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                val paddingValues = PaddingValues(
                    top = 0.dp,
                    bottom = paddingValues.calculateBottomPadding(),
                    start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                    end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
                )
                if (uiState.paths.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            modifier = Modifier
                                .padding(
                                    horizontal = itemHorizontalPadding,
                                ),
                            text = "保存されたフォーム入力はありません",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = with(LocalDensity.current) {
                                containerShape.topStart.toPx(Size.Unspecified, LocalDensity.current)
                                    .coerceAtLeast(containerShape.topStart.toPx(Size.Unspecified, LocalDensity.current))
                                    .toDp()
                            },
                            bottom = paddingValues.calculateBottomPadding(),
                        ),
                    ) {
                        items(uiState.paths, key = { it.path }) { entry ->
                            SiteFormInputPathListItem(
                                modifier = Modifier
                                    .testTag(SiteFormInputPathsScreenTestTags.PathEntry(entry.path).testTag)
                                    .fillMaxWidth(),
                                entry = entry,
                                onOpen = { uiState.callbacks.openPath(entry.path) },
                                contentPadding = PaddingValues(
                                    horizontal = itemHorizontalPadding,
                                    vertical = 8.dp,
                                )
                            )
                        }
                    }
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

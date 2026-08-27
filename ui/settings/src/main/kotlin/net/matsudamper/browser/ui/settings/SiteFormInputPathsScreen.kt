package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface SiteFormInputPathsScreenTestTags {
    val id: String
    val testTag get() = "${SiteFormInputPathsScreenTestTags::class.java.name}#$id"

    data object Root : SiteFormInputPathsScreenTestTags { override val id = "root" }

    data class PathEntry(val path: String) : SiteFormInputPathsScreenTestTags {
        override val id = "path_${path.replace('/', '_')}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteFormInputPathsScreen(
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
        if (uiState.paths.isEmpty()) {
            Text(
                text = "保存されたフォーム入力はありません",
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
                item {
                    Text(
                        text = uiState.host,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(uiState.paths, key = { it.path }) { entry ->
                    SiteFormInputPathListItem(
                        entry = entry,
                        onOpen = { uiState.callbacks.openPath(entry.path) },
                        onToggle = { enabled ->
                            uiState.callbacks.setPathEnabled(entry.path, enabled)
                        },
                        modifier = Modifier.testTag(
                            SiteFormInputPathsScreenTestTags.PathEntry(entry.path).testTag,
                        ),
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
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onOpen),
        headlineContent = { Text(entry.displayPath) },
        supportingContent = {
            Text("${entry.fieldCount} 件のフィールド")
        },
        trailingContent = {
            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggle,
            )
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun SiteFormInputPathsScreenPreview() {
    MaterialTheme {
        SiteFormInputPathsScreen(
            uiState = SiteFormInputPathsScreenUiState(
                callbacks = object : SiteFormInputPathsScreenUiState.Callbacks {
                    override fun navigateBack() = Unit
                    override fun setPathEnabled(path: String, enabled: Boolean) = Unit
                    override fun openPath(path: String) = Unit
                },
                host = "example.com",
                paths = listOf(
                    SiteFormInputPathsScreenUiState.PathEntry(
                        path = "/contact",
                        displayPath = "/contact",
                        fieldCount = 2,
                        enabled = true,
                    ),
                    SiteFormInputPathsScreenUiState.PathEntry(
                        path = "",
                        displayPath = "/",
                        fieldCount = 1,
                        enabled = false,
                    ),
                ),
            ),
        )
    }
}

package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.crashlog.CrashLogEntity
import net.matsudamper.browser.resources.R as ResourcesR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface CrashLogDetailScreenTestTags {
    val id: String

    val testTag get() = "${CrashLogDetailScreenTestTags::class.java.name}#$id"

    data object Root : CrashLogDetailScreenTestTags { override val id = "root" }
    data object Body : CrashLogDetailScreenTestTags { override val id = "body" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrashLogDetailScreen(
    uiState: CrashLogDetailScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = uiState.entry
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        modifier = modifier.testTag(CrashLogDetailScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text("クラッシュログ詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    if (entry != null) {
                        TextButton(onClick = uiState.callbacks::onClickCopyBody) {
                            Text("コピー")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            entry == null -> {
                Text(
                    text = "クラッシュログが見つかりません",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(16.dp),
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        text = dateFormat.format(Date(entry.occurredAt)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        text = entry.body,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .testTag(CrashLogDetailScreenTestTags.Body.testTag)
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewCrashLogDetailScreen() {
    CrashLogDetailScreen(
        uiState = CrashLogDetailScreenUiState(
            callbacks = object : CrashLogDetailScreenUiState.Callbacks {
                override fun onClickCopyBody() = Unit
            },
            isLoading = false,
            entry = CrashLogEntity(
                id = 1,
                occurredAt = 1_700_000_000_000,
                title = "java.lang.RuntimeException: test crash",
                body = "Thread: main\njava.lang.RuntimeException: test crash\n\tat example.MainActivity.onCreate(MainActivity.kt:10)",
            ),
        ),
        onBack = {},
    )
}

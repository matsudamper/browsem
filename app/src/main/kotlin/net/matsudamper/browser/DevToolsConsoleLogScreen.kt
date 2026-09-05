package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.feature.devtools.DevToolsWebExtension
import net.matsudamper.browser.ui.common.BrowserTheme
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoSession

@Composable
internal fun DevToolsConsoleLogDialog(
    session: GeckoSession,
    onDismiss: () -> Unit,
) {
    val uiState = rememberDevToolsConsoleLogUiState(
        session = session,
        onDismiss = onDismiss,
    )
    Dialog(
        onDismissRequest = { uiState.callbacks.onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DevToolsConsoleLogScreen(uiState = uiState)
        }
    }
}

@Composable
internal fun rememberDevToolsConsoleLogUiState(
    session: GeckoSession,
    onDismiss: () -> Unit,
): DevToolsConsoleLogUiState {
    val extension: DevToolsWebExtension = koinInject()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val consoleLogsBySession by extension.consoleLogs.collectAsState()
    val entries = consoleLogsBySession[session].orEmpty()
    val callbacks = remember(session, extension) {
        object : DevToolsConsoleLogUiState.Callbacks {
            override fun onDismiss() {
                currentOnDismiss()
            }

            override fun onClearLogs() {
                extension.clearConsoleLogs(session)
            }
        }
    }
    return DevToolsConsoleLogUiState(
        entries = entries,
        callbacks = callbacks,
    )
}

@Stable
internal data class DevToolsConsoleLogUiState(
    val entries: List<DevToolsWebExtension.ConsoleLogEntry>,
    val callbacks: Callbacks,
) {
    @Stable
    interface Callbacks {
        fun onDismiss()
        fun onClearLogs()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevToolsConsoleLogScreen(
    uiState: DevToolsConsoleLogUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.entries.size) {
        if (uiState.entries.isNotEmpty()) {
            listState.animateScrollToItem(uiState.entries.lastIndex)
        }
    }
    Scaffold(
        modifier = modifier.testTag(DevToolsConsoleLogTestTags.Screen.testTag),
        topBar = {
            TopAppBar(
                title = { Text("コンソールログ") },
                actions = {
                    TextButton(onClick = { uiState.callbacks.onClearLogs() }) {
                        Text("消去")
                    }
                    TextButton(onClick = { uiState.callbacks.onDismiss() }) {
                        Text("閉じる")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "console.log などの出力はここに表示されます",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(
                    items = uiState.entries,
                    key = { entry ->
                        "${entry.timestampMs}:${entry.level}:${entry.message}"
                    },
                ) { entry ->
                    ConsoleLogRow(entry = entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConsoleLogRow(entry: DevToolsWebExtension.ConsoleLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(consoleLevelBackground(entry.level))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = consoleLevelLabel(entry.level),
                style = MaterialTheme.typography.labelMedium,
                color = consoleLevelColor(entry.level),
            )
            Text(
                text = entry.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun consoleLevelBackground(level: DevToolsWebExtension.ConsoleLogEntry.Level): Color {
    return when (level) {
        DevToolsWebExtension.ConsoleLogEntry.Level.Error ->
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        DevToolsWebExtension.ConsoleLogEntry.Level.Warn ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
}

@Composable
private fun consoleLevelColor(level: DevToolsWebExtension.ConsoleLogEntry.Level): Color {
    return when (level) {
        DevToolsWebExtension.ConsoleLogEntry.Level.Error -> MaterialTheme.colorScheme.error
        DevToolsWebExtension.ConsoleLogEntry.Level.Warn -> MaterialTheme.colorScheme.tertiary
        DevToolsWebExtension.ConsoleLogEntry.Level.Info -> MaterialTheme.colorScheme.primary
        DevToolsWebExtension.ConsoleLogEntry.Level.Debug -> MaterialTheme.colorScheme.secondary
        DevToolsWebExtension.ConsoleLogEntry.Level.Log -> MaterialTheme.colorScheme.onSurface
    }
}

private fun consoleLevelLabel(level: DevToolsWebExtension.ConsoleLogEntry.Level): String {
    return when (level) {
        DevToolsWebExtension.ConsoleLogEntry.Level.Log -> "LOG"
        DevToolsWebExtension.ConsoleLogEntry.Level.Warn -> "WARN"
        DevToolsWebExtension.ConsoleLogEntry.Level.Error -> "ERROR"
        DevToolsWebExtension.ConsoleLogEntry.Level.Info -> "INFO"
        DevToolsWebExtension.ConsoleLogEntry.Level.Debug -> "DEBUG"
    }
}

sealed interface DevToolsConsoleLogTestTags {
    val id: String
    val testTag get() = "${DevToolsConsoleLogTestTags::class.java.name}#$id"

    object Screen : DevToolsConsoleLogTestTags {
        override val id = "screen"
    }
}

@Preview(name = "ログあり")
@Composable
private fun PreviewDevToolsConsoleLogScreenWithEntries() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleLogScreen(
            uiState = DevToolsConsoleLogUiState(
                entries = listOf(
                    DevToolsWebExtension.ConsoleLogEntry(
                        level = DevToolsWebExtension.ConsoleLogEntry.Level.Log,
                        message = "hello world",
                        url = "https://example.com/",
                        timestampMs = 1L,
                    ),
                    DevToolsWebExtension.ConsoleLogEntry(
                        level = DevToolsWebExtension.ConsoleLogEntry.Level.Error,
                        message = "something failed",
                        url = "https://example.com/",
                        timestampMs = 2L,
                    ),
                ),
                callbacks = PreviewDevToolsConsoleLogCallbacks,
            ),
        )
    }
}

@Preview(name = "ログなし")
@Composable
private fun PreviewDevToolsConsoleLogScreenEmpty() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleLogScreen(
            uiState = DevToolsConsoleLogUiState(
                entries = listOf(),
                callbacks = PreviewDevToolsConsoleLogCallbacks,
            ),
        )
    }
}

private object PreviewDevToolsConsoleLogCallbacks : DevToolsConsoleLogUiState.Callbacks {
    override fun onDismiss() = Unit
    override fun onClearLogs() = Unit
}

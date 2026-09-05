package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import net.matsudamper.browser.ui.common.StatusBarAppearanceEffect
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoSession

@Composable
internal fun DevToolsConsoleDialog(
    session: GeckoSession,
    onDismiss: () -> Unit,
) {
    val uiState = rememberDevToolsConsoleUiState(
        session = session,
        onDismiss = onDismiss,
    )
    Dialog(
        onDismissRequest = { uiState.callbacks.onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            StatusBarAppearanceEffect(MaterialTheme.colorScheme.surface)
            DevToolsConsoleScreen(uiState = uiState)
        }
    }
}

@Composable
internal fun rememberDevToolsConsoleUiState(
    session: GeckoSession,
    onDismiss: () -> Unit,
): DevToolsConsoleUiState {
    val extension: DevToolsWebExtension = koinInject()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val consoleLogsBySession by extension.consoleLogs.collectAsState()
    val entries = consoleLogsBySession[session].orEmpty()
    var scriptText by remember { mutableStateOf("") }
    var executionState by remember {
        mutableStateOf<DevToolsConsoleUiState.ExecutionState>(
            DevToolsConsoleUiState.ExecutionState.Idle,
        )
    }
    val callbacks = remember(session, extension) {
        object : DevToolsConsoleUiState.Callbacks {
            override fun onDismiss() {
                currentOnDismiss()
            }

            override fun onClearLogs() {
                extension.clearConsoleLogs(session)
            }

            override fun onScriptTextChange(text: String) {
                scriptText = text
            }

            override fun onExecuteScript() {
                if (scriptText.isBlank()) {
                    executionState = DevToolsConsoleUiState.ExecutionState.Error("スクリプトが空です")
                    return
                }
                executionState = DevToolsConsoleUiState.ExecutionState.Running
                extension.executeScript(session, scriptText) { result ->
                    executionState = when (result) {
                        is DevToolsWebExtension.ScriptExecutionResult.Success ->
                            DevToolsConsoleUiState.ExecutionState.Success(result.result)
                        is DevToolsWebExtension.ScriptExecutionResult.Failure ->
                            DevToolsConsoleUiState.ExecutionState.Error(result.message)
                    }
                }
            }
        }
    }
    return DevToolsConsoleUiState(
        entries = entries,
        scriptText = scriptText,
        executionState = executionState,
        callbacks = callbacks,
    )
}

@Stable
internal data class DevToolsConsoleUiState(
    val entries: List<DevToolsWebExtension.ConsoleLogEntry>,
    val scriptText: String,
    val executionState: ExecutionState,
    val callbacks: Callbacks,
) {
    @Stable
    sealed interface ExecutionState {
        data object Idle : ExecutionState
        data object Running : ExecutionState
        data class Success(val result: String) : ExecutionState
        data class Error(val message: String) : ExecutionState
    }

    @Stable
    interface Callbacks {
        fun onDismiss()
        fun onClearLogs()
        fun onScriptTextChange(text: String)
        fun onExecuteScript()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevToolsConsoleScreen(
    uiState: DevToolsConsoleUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollTargetId = uiState.entries.lastOrNull()?.id
    LaunchedEffect(scrollTargetId) {
        if (uiState.entries.isNotEmpty()) {
            listState.animateScrollToItem(uiState.entries.lastIndex)
        }
    }
    Scaffold(
        modifier = modifier.testTag(DevToolsConsoleTestTags.Screen.testTag),
        topBar = {
            TopAppBar(
                title = { Text("コンソール") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (uiState.entries.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        text = "console.log などの出力がここに表示されます",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(
                                items = uiState.entries,
                                key = { entry -> entry.id },
                            ) { entry ->
                                ConsoleLogRow(entry = entry)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            ExecutionFeedbackSection(executionState = uiState.executionState)
            HorizontalDivider()
            ConsoleInputSection(uiState = uiState)
        }
    }
}

@Composable
private fun ExecutionFeedbackSection(
    executionState: DevToolsConsoleUiState.ExecutionState,
) {
    when (executionState) {
        DevToolsConsoleUiState.ExecutionState.Idle -> Unit
        DevToolsConsoleUiState.ExecutionState.Running -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text(text = "実行中…")
            }
        }
        is DevToolsConsoleUiState.ExecutionState.Success -> {
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(DevToolsConsoleTestTags.Result.testTag),
                    text = "← ${executionState.result}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is DevToolsConsoleUiState.ExecutionState.Error -> {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(DevToolsConsoleTestTags.Result.testTag),
                text = executionState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ConsoleInputSection(
    uiState: DevToolsConsoleUiState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            modifier = Modifier
                .weight(1f)
                .testTag(DevToolsConsoleTestTags.ScriptInput.testTag),
            value = uiState.scriptText,
            onValueChange = uiState.callbacks::onScriptTextChange,
            label = { Text("JavaScript") },
            minLines = 1,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Button(
            modifier = Modifier.testTag(DevToolsConsoleTestTags.ExecuteButton.testTag),
            onClick = uiState.callbacks::onExecuteScript,
            enabled = uiState.executionState != DevToolsConsoleUiState.ExecutionState.Running,
        ) {
            Text("実行")
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

sealed interface DevToolsConsoleTestTags {
    val id: String
    val testTag get() = "${DevToolsConsoleTestTags::class.java.name}#$id"

    object Screen : DevToolsConsoleTestTags {
        override val id = "screen"
    }
    object ScriptInput : DevToolsConsoleTestTags {
        override val id = "script_input"
    }
    object ExecuteButton : DevToolsConsoleTestTags {
        override val id = "execute_button"
    }
    object Result : DevToolsConsoleTestTags {
        override val id = "result"
    }
}

@Preview(name = "ログあり")
@Composable
private fun PreviewDevToolsConsoleScreenWithEntries() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleScreen(
            uiState = DevToolsConsoleUiState(
                entries = listOf(
                    DevToolsWebExtension.ConsoleLogEntry(
                        id = 1L,
                        level = DevToolsWebExtension.ConsoleLogEntry.Level.Log,
                        message = "hello world",
                        url = "https://example.com/",
                        timestampMs = 1L,
                    ),
                ),
                scriptText = "1 + 2",
                executionState = DevToolsConsoleUiState.ExecutionState.Idle,
                callbacks = PreviewDevToolsConsoleCallbacks,
            ),
        )
    }
}

@Preview(name = "実行成功")
@Composable
private fun PreviewDevToolsConsoleScreenSuccess() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleScreen(
            uiState = DevToolsConsoleUiState(
                entries = listOf(),
                scriptText = "1 + 2",
                executionState = DevToolsConsoleUiState.ExecutionState.Success("3"),
                callbacks = PreviewDevToolsConsoleCallbacks,
            ),
        )
    }
}

private object PreviewDevToolsConsoleCallbacks : DevToolsConsoleUiState.Callbacks {
    override fun onDismiss() = Unit
    override fun onClearLogs() = Unit
    override fun onScriptTextChange(text: String) = Unit
    override fun onExecuteScript() = Unit
}

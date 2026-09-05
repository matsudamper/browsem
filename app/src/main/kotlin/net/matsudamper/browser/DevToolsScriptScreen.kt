package net.matsudamper.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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
internal fun DevToolsScriptDialog(
    session: GeckoSession,
    onDismiss: () -> Unit,
) {
    val uiState = rememberDevToolsScriptUiState(
        session = session,
        onDismiss = onDismiss,
    )
    Dialog(
        onDismissRequest = { uiState.callbacks.onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DevToolsScriptScreen(uiState = uiState)
        }
    }
}

@Composable
internal fun rememberDevToolsScriptUiState(
    session: GeckoSession,
    onDismiss: () -> Unit,
): DevToolsScriptUiState {
    val extension: DevToolsWebExtension = koinInject()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var scriptText by remember { mutableStateOf("") }
    var executionState by remember { mutableStateOf<DevToolsScriptUiState.ExecutionState>(
        DevToolsScriptUiState.ExecutionState.Idle,
    ) }
    val callbacks = remember(session, extension) {
        object : DevToolsScriptUiState.Callbacks {
            override fun onDismiss() {
                currentOnDismiss()
            }

            override fun onScriptTextChange(text: String) {
                scriptText = text
            }

            override fun onExecuteScript() {
                if (scriptText.isBlank()) {
                    executionState = DevToolsScriptUiState.ExecutionState.Error("スクリプトが空です")
                    return
                }
                executionState = DevToolsScriptUiState.ExecutionState.Running
                extension.executeScript(session, scriptText) { result ->
                    executionState = when (result) {
                        is DevToolsWebExtension.ScriptExecutionResult.Success ->
                            DevToolsScriptUiState.ExecutionState.Success(result.result)
                        is DevToolsWebExtension.ScriptExecutionResult.Failure ->
                            DevToolsScriptUiState.ExecutionState.Error(result.message)
                    }
                }
            }
        }
    }
    return DevToolsScriptUiState(
        scriptText = scriptText,
        executionState = executionState,
        callbacks = callbacks,
    )
}

@Stable
internal data class DevToolsScriptUiState(
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
        fun onScriptTextChange(text: String)
        fun onExecuteScript()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevToolsScriptScreen(
    uiState: DevToolsScriptUiState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(DevToolsScriptTestTags.Screen.testTag),
        topBar = {
            TopAppBar(
                title = { Text("スクリプト実行") },
                actions = {
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
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DevToolsScriptTestTags.ScriptInput.testTag),
                value = uiState.scriptText,
                onValueChange = uiState.callbacks::onScriptTextChange,
                label = { Text("JavaScript") },
                minLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Button(
                modifier = Modifier.testTag(DevToolsScriptTestTags.ExecuteButton.testTag),
                onClick = uiState.callbacks::onExecuteScript,
                enabled = uiState.executionState != DevToolsScriptUiState.ExecutionState.Running,
            ) {
                Text("実行")
            }
            ExecutionResultSection(executionState = uiState.executionState)
        }
    }
}

@Composable
private fun ExecutionResultSection(
    executionState: DevToolsScriptUiState.ExecutionState,
) {
    when (executionState) {
        DevToolsScriptUiState.ExecutionState.Idle -> Unit
        DevToolsScriptUiState.ExecutionState.Running -> {
            CircularProgressIndicator()
        }
        is DevToolsScriptUiState.ExecutionState.Success -> {
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "結果",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(DevToolsScriptTestTags.Result.testTag),
                        text = executionState.result,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
        is DevToolsScriptUiState.ExecutionState.Error -> {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DevToolsScriptTestTags.Result.testTag),
                text = executionState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

sealed interface DevToolsScriptTestTags {
    val id: String
    val testTag get() = "${DevToolsScriptTestTags::class.java.name}#$id"

    object Screen : DevToolsScriptTestTags {
        override val id = "screen"
    }
    object ScriptInput : DevToolsScriptTestTags {
        override val id = "script_input"
    }
    object ExecuteButton : DevToolsScriptTestTags {
        override val id = "execute_button"
    }
    object Result : DevToolsScriptTestTags {
        override val id = "result"
    }
}

@Preview(name = "実行前")
@Composable
private fun PreviewDevToolsScriptScreenIdle() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsScriptScreen(
            uiState = DevToolsScriptUiState(
                scriptText = "1 + 2",
                executionState = DevToolsScriptUiState.ExecutionState.Idle,
                callbacks = PreviewDevToolsScriptCallbacks,
            ),
        )
    }
}

@Preview(name = "実行成功")
@Composable
private fun PreviewDevToolsScriptScreenSuccess() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsScriptScreen(
            uiState = DevToolsScriptUiState(
                scriptText = "1 + 2",
                executionState = DevToolsScriptUiState.ExecutionState.Success("3"),
                callbacks = PreviewDevToolsScriptCallbacks,
            ),
        )
    }
}

private object PreviewDevToolsScriptCallbacks : DevToolsScriptUiState.Callbacks {
    override fun onDismiss() = Unit
    override fun onScriptTextChange(text: String) = Unit
    override fun onExecuteScript() = Unit
}

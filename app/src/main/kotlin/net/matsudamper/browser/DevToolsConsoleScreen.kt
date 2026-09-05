package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.resources.R as ResourcesR
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.ui.common.StatusBarAppearanceEffect

// 1 行に表示する最大行数。これを超える出力はタップして全文を見る
private const val CONSOLE_ENTRY_MAX_LINES = 8

/**
 * コンソールを全画面ダイアログで表示する。
 * ブラウザのタブ上に重ねて出すため、タブ内の状態を保ったまま開閉できる。
 */
@Composable
internal fun DevToolsConsoleDialog(uiState: DevToolsConsoleUiState) {
    Dialog(
        // 全文表示中は、OS の戻る操作では画面ごと閉じずに一覧へ戻す
        onDismissRequest = {
            if (uiState.detail != null) {
                uiState.callbacks.onClickCloseDetail()
            } else {
                uiState.callbacks.onDismiss()
            }
        },
        // targetSdk 35 以降は decorFitsSystemWindows が無視されるため、
        // ダイアログ側でシステムバー・IME のインセットを自前で避ける。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            StatusBarAppearanceEffect(MaterialTheme.colorScheme.surface)
            DevToolsConsoleScreen(
                uiState = uiState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

/**
 * コンソール画面。
 * 一覧と全文表示を 1 画面で切り替える。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevToolsConsoleScreen(
    uiState: DevToolsConsoleUiState,
    modifier: Modifier = Modifier,
) {
    val detail = uiState.detail
    Scaffold(
        modifier = modifier.testTag(DevToolsConsoleTestTags.Screen.testTag),
        topBar = {
            if (detail == null) {
                TopAppBar(
                    title = { Text("コンソール") },
                    navigationIcon = {
                        IconButton(onClick = uiState.callbacks::onDismiss) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.close_24dp),
                                contentDescription = "閉じる",
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            modifier = Modifier.testTag(DevToolsConsoleTestTags.ClearButton.testTag),
                            onClick = uiState.callbacks::onClickClear,
                        ) {
                            Text("消去")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(detail.title) },
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.testTag(DevToolsConsoleTestTags.DetailBackButton.testTag),
                            onClick = uiState.callbacks::onClickCloseDetail,
                        ) {
                            Icon(
                                painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                                contentDescription = "一覧へ戻る",
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            modifier = Modifier.testTag(DevToolsConsoleTestTags.DetailCopyButton.testTag),
                            onClick = uiState.callbacks::onClickCopyDetail,
                        ) {
                            Text("全文をコピー")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        if (detail == null) {
            ConsoleListContent(
                uiState = uiState,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            ConsoleDetailContent(
                detail = detail,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ConsoleListContent(
    uiState: DevToolsConsoleUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 出力が増えたら最新の行が見えるようにする
    LaunchedEffect(uiState.entries.lastOrNull()?.id) {
        if (uiState.entries.isNotEmpty()) {
            listState.scrollToItem(uiState.entries.lastIndex)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        items = uiState.entries,
                        key = { entry -> entry.id },
                    ) { entry ->
                        ConsoleEntryRow(entry = entry)
                        HorizontalDivider()
                    }
                }
            }
        }
        HorizontalDivider()
        ConsoleInput(uiState = uiState)
    }
}

@Composable
private fun ConsoleDetailContent(
    detail: DevToolsConsoleUiState.Detail,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (detail.url != null) {
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = detail.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DevToolsConsoleTestTags.DetailMessage.testTag),
                text = detail.message,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun ConsoleEntryRow(entry: DevToolsConsoleUiState.Entry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(entryBackgroundColor(entry.kind))
            .clickable(onClick = entry.listener::onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelMedium,
                color = entryLabelColor(entry.kind),
            )
            if (entry.url != null) {
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    text = entry.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = CONSOLE_ENTRY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConsoleInput(uiState: DevToolsConsoleUiState) {
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
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Button(
            modifier = Modifier.testTag(DevToolsConsoleTestTags.ExecuteButton.testTag),
            onClick = uiState.callbacks::onClickExecute,
            enabled = uiState.canExecute,
        ) {
            Text(if (uiState.isExecuting) "実行中" else "実行")
        }
    }
}

@Composable
private fun entryBackgroundColor(kind: DevToolsConsoleUiState.Kind): Color {
    return when (kind) {
        DevToolsConsoleUiState.Kind.Error,
        DevToolsConsoleUiState.Kind.ResultError,
        -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)

        DevToolsConsoleUiState.Kind.Warn ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)

        DevToolsConsoleUiState.Kind.Input ->
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

        DevToolsConsoleUiState.Kind.Log,
        DevToolsConsoleUiState.Kind.Info,
        DevToolsConsoleUiState.Kind.Debug,
        DevToolsConsoleUiState.Kind.Result,
        -> Color.Transparent
    }
}

@Composable
private fun entryLabelColor(kind: DevToolsConsoleUiState.Kind): Color {
    return when (kind) {
        DevToolsConsoleUiState.Kind.Error,
        DevToolsConsoleUiState.Kind.ResultError,
        -> MaterialTheme.colorScheme.error

        DevToolsConsoleUiState.Kind.Warn -> MaterialTheme.colorScheme.tertiary
        DevToolsConsoleUiState.Kind.Info,
        DevToolsConsoleUiState.Kind.Result,
        -> MaterialTheme.colorScheme.primary

        DevToolsConsoleUiState.Kind.Debug -> MaterialTheme.colorScheme.secondary
        DevToolsConsoleUiState.Kind.Log,
        DevToolsConsoleUiState.Kind.Input,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

sealed interface DevToolsConsoleTestTags {
    val id: String
    val testTag get() = "${DevToolsConsoleTestTags::class.java.name}#$id"

    object Screen : DevToolsConsoleTestTags {
        override val id = "screen"
    }
    object ClearButton : DevToolsConsoleTestTags {
        override val id = "clear_button"
    }
    object ScriptInput : DevToolsConsoleTestTags {
        override val id = "script_input"
    }
    object ExecuteButton : DevToolsConsoleTestTags {
        override val id = "execute_button"
    }
    object DetailBackButton : DevToolsConsoleTestTags {
        override val id = "detail_back_button"
    }
    object DetailCopyButton : DevToolsConsoleTestTags {
        override val id = "detail_copy_button"
    }
    object DetailMessage : DevToolsConsoleTestTags {
        override val id = "detail_message"
    }
}

private object PreviewDevToolsConsoleEntryListener : DevToolsConsoleUiState.Entry.Listener {
    override fun onClick() = Unit
}

private object PreviewDevToolsConsoleCallbacks : DevToolsConsoleUiState.Callbacks {
    override fun onScriptTextChange(text: String) = Unit
    override fun onClickExecute() = Unit
    override fun onClickClear() = Unit
    override fun onClickCloseDetail() = Unit
    override fun onClickCopyDetail() = Unit
    override fun onDismiss() = Unit
}

@Preview(name = "コンソール一覧")
@Composable
private fun PreviewDevToolsConsoleScreen() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleScreen(
            uiState = DevToolsConsoleUiState(
                callbacks = PreviewDevToolsConsoleCallbacks,
                entries = listOf(
                    DevToolsConsoleUiState.Entry(
                        id = 1,
                        kind = DevToolsConsoleUiState.Kind.Log,
                        label = "LOG",
                        message = "hello world",
                        url = "https://example.com/",
                        listener = PreviewDevToolsConsoleEntryListener,
                    ),
                    DevToolsConsoleUiState.Entry(
                        id = 2,
                        kind = DevToolsConsoleUiState.Kind.Error,
                        label = "ERROR",
                        message = (1..12).joinToString("\n") { line -> "error line $line" },
                        url = "https://example.com/app.js",
                        listener = PreviewDevToolsConsoleEntryListener,
                    ),
                    DevToolsConsoleUiState.Entry(
                        id = 3,
                        kind = DevToolsConsoleUiState.Kind.Input,
                        label = ">",
                        message = "1 + 2",
                        url = null,
                        listener = PreviewDevToolsConsoleEntryListener,
                    ),
                    DevToolsConsoleUiState.Entry(
                        id = 4,
                        kind = DevToolsConsoleUiState.Kind.Result,
                        label = "←",
                        message = "3",
                        url = null,
                        listener = PreviewDevToolsConsoleEntryListener,
                    ),
                ),
                scriptText = "document.title",
                canExecute = true,
                isExecuting = false,
                detail = null,
            ),
        )
    }
}

@Preview(name = "コンソール（出力なし）")
@Composable
private fun PreviewDevToolsConsoleScreenEmpty() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleScreen(
            uiState = DevToolsConsoleUiState(
                callbacks = PreviewDevToolsConsoleCallbacks,
                entries = listOf(),
                scriptText = "",
                canExecute = false,
                isExecuting = false,
                detail = null,
            ),
        )
    }
}

@Preview(name = "コンソール全文表示")
@Composable
private fun PreviewDevToolsConsoleScreenDetail() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DevToolsConsoleScreen(
            uiState = DevToolsConsoleUiState(
                callbacks = PreviewDevToolsConsoleCallbacks,
                entries = listOf(),
                scriptText = "",
                canExecute = false,
                isExecuting = false,
                detail = DevToolsConsoleUiState.Detail(
                    title = "ERROR",
                    message = (1..12).joinToString("\n") { line -> "error line $line" },
                    url = "https://example.com/app.js",
                ),
            ),
        )
    }
}

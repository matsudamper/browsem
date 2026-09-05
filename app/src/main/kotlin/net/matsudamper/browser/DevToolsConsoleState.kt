package net.matsudamper.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import net.matsudamper.browser.feature.devtools.DevToolsWebExtension
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoSession

/**
 * コンソール画面の UiState を組み立てる。
 * ページの console 出力の収集は [DevToolsWebExtension] が行い、
 * ここでは表示中のタブの分を表示に変換する。
 */
@Composable
internal fun rememberDevToolsConsoleUiState(
    session: GeckoSession,
    onDismiss: () -> Unit,
): DevToolsConsoleUiState {
    val context = LocalContext.current
    val extension: DevToolsWebExtension = koinInject()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val holder = remember(session, extension) {
        DevToolsConsoleStateHolder(
            session = session,
            extension = extension,
            context = context,
            onDismissRequest = { currentOnDismiss() },
        )
    }
    // 画面を開いている間だけページ側の console 出力を転送させる
    DisposableEffect(holder) {
        extension.setConsoleWatching(session, true)
        onDispose {
            extension.setConsoleWatching(session, false)
        }
    }
    val entriesBySession by extension.consoleEntries.collectAsState()
    return holder.createUiState(entries = entriesBySession[session].orEmpty())
}

/**
 * コンソール画面の状態保持。
 */
@Stable
internal class DevToolsConsoleStateHolder(
    private val session: GeckoSession,
    private val extension: DevToolsWebExtension,
    private val context: Context,
    private val onDismissRequest: () -> Unit,
) {
    private var scriptText by mutableStateOf("")
    private var isExecuting by mutableStateOf(false)

    /** 全文表示中の行。一覧表示中は null */
    private var selectedEntryId by mutableStateOf<Long?>(null)

    private val callbacks = object : DevToolsConsoleUiState.Callbacks {
        override fun onScriptTextChange(text: String) {
            scriptText = text
        }

        override fun onClickExecute() {
            if (isExecuting || scriptText.isBlank()) return
            isExecuting = true
            extension.executeScript(session, scriptText) {
                isExecuting = false
            }
            scriptText = ""
        }

        override fun onClickClear() {
            selectedEntryId = null
            extension.clearConsoleEntries(session)
        }

        override fun onClickCloseDetail() {
            selectedEntryId = null
        }

        override fun onClickCopyDetail() {
            val message = selectedEntry()?.message ?: return
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("console", message))
            Toast.makeText(context, "全文をコピーしました", Toast.LENGTH_SHORT).show()
        }

        override fun onDismiss() {
            onDismissRequest()
        }
    }

    private var currentEntries: List<DevToolsWebExtension.ConsoleEntry> = listOf()

    fun createUiState(
        entries: List<DevToolsWebExtension.ConsoleEntry>,
    ): DevToolsConsoleUiState {
        currentEntries = entries
        val selected = selectedEntry()
        return DevToolsConsoleUiState(
            callbacks = callbacks,
            entries = entries.map { entry -> createEntry(entry) },
            scriptText = scriptText,
            canExecute = scriptText.isNotBlank() && !isExecuting,
            isExecuting = isExecuting,
            detail = if (selected == null) {
                null
            } else {
                DevToolsConsoleUiState.Detail(
                    title = entryLabel(selected.kind),
                    message = selected.message,
                    url = selected.url.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    private fun selectedEntry(): DevToolsWebExtension.ConsoleEntry? {
        val id = selectedEntryId ?: return null
        return currentEntries.firstOrNull { entry -> entry.id == id }
    }

    private fun createEntry(
        entry: DevToolsWebExtension.ConsoleEntry,
    ): DevToolsConsoleUiState.Entry {
        return DevToolsConsoleUiState.Entry(
            id = entry.id,
            kind = entryKind(entry.kind),
            label = entryLabel(entry.kind),
            message = entry.message,
            url = entry.url.takeIf { it.isNotBlank() },
            listener = object : DevToolsConsoleUiState.Entry.Listener {
                override fun onClick() {
                    selectedEntryId = entry.id
                }
            },
        )
    }

    private fun entryKind(
        kind: DevToolsWebExtension.ConsoleEntry.Kind,
    ): DevToolsConsoleUiState.Kind {
        return when (kind) {
            DevToolsWebExtension.ConsoleEntry.Kind.Log -> DevToolsConsoleUiState.Kind.Log

            DevToolsWebExtension.ConsoleEntry.Kind.Info -> DevToolsConsoleUiState.Kind.Info

            DevToolsWebExtension.ConsoleEntry.Kind.Warn -> DevToolsConsoleUiState.Kind.Warn

            DevToolsWebExtension.ConsoleEntry.Kind.Error -> DevToolsConsoleUiState.Kind.Error

            DevToolsWebExtension.ConsoleEntry.Kind.Debug -> DevToolsConsoleUiState.Kind.Debug

            DevToolsWebExtension.ConsoleEntry.Kind.Input -> DevToolsConsoleUiState.Kind.Input

            DevToolsWebExtension.ConsoleEntry.Kind.Result -> DevToolsConsoleUiState.Kind.Result

            DevToolsWebExtension.ConsoleEntry.Kind.ResultError ->
                DevToolsConsoleUiState.Kind.ResultError
        }
    }

    private fun entryLabel(kind: DevToolsWebExtension.ConsoleEntry.Kind): String {
        return when (kind) {
            DevToolsWebExtension.ConsoleEntry.Kind.Log -> "LOG"
            DevToolsWebExtension.ConsoleEntry.Kind.Info -> "INFO"
            DevToolsWebExtension.ConsoleEntry.Kind.Warn -> "WARN"
            DevToolsWebExtension.ConsoleEntry.Kind.Error -> "ERROR"
            DevToolsWebExtension.ConsoleEntry.Kind.Debug -> "DEBUG"
            DevToolsWebExtension.ConsoleEntry.Kind.Input -> ">"
            DevToolsWebExtension.ConsoleEntry.Kind.Result -> "←"
            DevToolsWebExtension.ConsoleEntry.Kind.ResultError -> "実行エラー"
        }
    }
}

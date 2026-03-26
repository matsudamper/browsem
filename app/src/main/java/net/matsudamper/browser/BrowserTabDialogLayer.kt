package net.matsudamper.browser

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import org.mozilla.geckoview.GeckoSession

@Composable
internal fun BrowserTabDialogLayer(
    state: BrowserTabScreenState,
    dialogState: PromptDialogState,
    enableTabUi: Boolean,
    onOpenNewSessionRequest: (String) -> GeckoSession,
) {
    val context = LocalContext.current

    // ファイルピッカー（ActivityResultLauncher）をトップレベルで登録
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            dialogState.confirmFilePromptWithUri(context, uri)
        } else {
            dialogState.dismissFilePrompt()
        }
    }

    state.imageContextMenuUrl?.let { imageUrl ->
        AlertDialog(
            onDismissRequest = { state.imageContextMenuUrl = null },
            title = { Text(text = "画像") },
            text = { Text(text = "この画像をダウンロードしますか？") },
            confirmButton = {
                TextButton(onClick = { state.downloadImage(imageUrl) }) {
                    Text(text = "ダウンロード")
                }
            },
            dismissButton = {
                TextButton(onClick = { state.imageContextMenuUrl = null }) {
                    Text(text = "キャンセル")
                }
            },
        )
    }

    state.linkContextMenuUrl?.let { linkUrl ->
        AlertDialog(
            onDismissRequest = { state.linkContextMenuUrl = null },
            title = { Text(text = "リンク") },
            text = {
                Text(
                    text = linkUrl,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = { state.copyLinkUrl(linkUrl) }) {
                    Text("URLをコピー")
                }
            },
            dismissButton = {
                Column {
                    if (enableTabUi) {
                        TextButton(
                            onClick = {
                                onOpenNewSessionRequest(linkUrl)
                                state.linkContextMenuUrl = null
                            },
                        ) {
                            Text("新しいタブで開く")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                state.onUrlSubmit(linkUrl)
                                state.linkContextMenuUrl = null
                            },
                        ) {
                            Text("開く")
                        }
                    }
                    TextButton(onClick = { state.linkContextMenuUrl = null }) {
                        Text("キャンセル")
                    }
                }
            },
        )
    }

    state.pendingDownloadResponse?.let { response ->
        AlertDialog(
            onDismissRequest = state::dismissPendingDownload,
            title = { Text("ダウンロード") },
            text = {
                Text(
                    text = response.uri,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = state::confirmPendingDownload) {
                    Text("ダウンロード")
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissPendingDownload) {
                    Text("キャンセル")
                }
            },
        )
    }

    state.pendingExternalAppLaunch?.let { request ->
        AlertDialog(
            onDismissRequest = {
                state.dismissPendingExternalAppLaunch()
            },
            title = { Text("アプリを開く") },
            text = {
                Column {
                    if (request.appName != null) {
                        Text("${request.appName} をアプリで開きますか？")
                    } else {
                        Text("このリンクをアプリで開きますか？")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        request.sourceUri,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = state::confirmPendingExternalAppLaunch) {
                    Text("開く")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.dismissPendingExternalAppLaunchAndLoadInBrowser()
                }) {
                    Text("キャンセル")
                }
            },
        )
    }

    dialogState.pendingAlertPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = dialogState::dismissAlertPrompt,
            text = { Text(prompt.message ?: "") },
            confirmButton = {
                TextButton(onClick = dialogState::dismissAlertPrompt) {
                    Text("OK")
                }
            },
        )
    }

    dialogState.pendingButtonPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = dialogState::dismissButtonPrompt,
            text = { Text(prompt.message ?: "") },
            confirmButton = {
                TextButton(onClick = { dialogState.confirmButtonPrompt(true) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.confirmButtonPrompt(false) }) {
                    Text("キャンセル")
                }
            },
        )
    }

    dialogState.pendingTextPrompt?.let { prompt ->
        var textValue by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "") }
        AlertDialog(
            onDismissRequest = dialogState::dismissTextPrompt,
            title = prompt.message?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { dialogState.confirmTextPrompt(textValue) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = dialogState::dismissTextPrompt) {
                    Text("キャンセル")
                }
            },
        )
    }

    dialogState.pendingChoicePrompt?.let { prompt ->
        ChoicePromptDialog(
            prompt = prompt,
            onDismiss = dialogState::dismissChoicePrompt,
            onConfirmSingle = dialogState::confirmChoicePromptSingle,
            onConfirmMultiple = dialogState::confirmChoicePromptMultiple,
        )
    }

    dialogState.pendingColorPrompt?.let { prompt ->
        var colorText by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "#000000") }
        val parsedColor = remember(colorText) {
            runCatching { Color(colorText.toColorInt()) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = dialogState::dismissColorPrompt,
            title = { Text("色を選択") },
            text = {
                Column {
                    if (parsedColor != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(parsedColor),
                        )
                    }
                    OutlinedTextField(
                        value = colorText,
                        onValueChange = { colorText = it },
                        label = { Text("#RRGGBB") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { dialogState.confirmColorPrompt(colorText) },
                    enabled = parsedColor != null,
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = dialogState::dismissColorPrompt) {
                    Text("キャンセル")
                }
            },
        )
    }

    dialogState.pendingDateTimePrompt?.let { prompt ->
        var dateTimeText by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "") }
        val (title, hint) = when (prompt.type) {
            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE ->
                "日付を選択" to "YYYY-MM-DD"

            GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME ->
                "時刻を選択" to "HH:MM"

            GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH ->
                "年月を選択" to "YYYY-MM"

            GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK ->
                "週を選択" to "YYYY-Www"

            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL ->
                "日時を選択" to "YYYY-MM-DDTHH:MM"

            else -> "値を入力" to ""
        }
        AlertDialog(
            onDismissRequest = dialogState::dismissDateTimePrompt,
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = dateTimeText,
                    onValueChange = { dateTimeText = it },
                    label = { Text(hint) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { dialogState.confirmDateTimePrompt(dateTimeText) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = dialogState::dismissDateTimePrompt) {
                    Text("キャンセル")
                }
            },
        )
    }

    dialogState.pendingFilePrompt?.let { prompt ->
        // クリップボードに画像があるか確認
        val clipboardImageUri = remember(prompt) { getClipboardImageUri(context) }
        AlertDialog(
            modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                // Ctrl+V でクリップボードから画像を貼り付け
                if (keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.isCtrlPressed &&
                    keyEvent.key == Key.V
                ) {
                    val uri = getClipboardImageUri(context)
                    if (uri != null) {
                        dialogState.confirmFilePromptWithUri(context, uri)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
            onDismissRequest = dialogState::dismissFilePrompt,
            title = { Text("ファイルを選択") },
            text = {
                if (clipboardImageUri != null) {
                    Text("ファイルを選択するか、クリップボードの画像を貼り付けてください。")
                } else {
                    Text("アップロードするファイルを選択してください。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mimeTypes = prompt.mimeTypes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
                        filePickerLauncher.launch(mimeTypes)
                    },
                ) {
                    Text("ファイルを選択")
                }
            },
            dismissButton = {
                Column {
                    if (clipboardImageUri != null) {
                        TextButton(
                            onClick = { dialogState.confirmFilePromptWithUri(context, clipboardImageUri) },
                        ) {
                            Text("クリップボードから貼り付け (Ctrl+V)")
                        }
                    }
                    TextButton(onClick = dialogState::dismissFilePrompt) {
                        Text("キャンセル")
                    }
                }
            },
        )
    }
}

@Composable
private fun ChoicePromptDialog(
    prompt: GeckoSession.PromptDelegate.ChoicePrompt,
    onDismiss: () -> Unit,
    onConfirmSingle: (GeckoSession.PromptDelegate.ChoicePrompt.Choice) -> Unit,
    onConfirmMultiple: (Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>) -> Unit,
) {
    val isMultiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
    val flatChoices = remember(prompt) { flattenChoices(prompt.choices) }
    val selectedIds = remember(prompt) {
        mutableStateOf(flatChoices.filter { it.selected }.map { it.id }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            LazyColumn {
                items(flatChoices) { choice ->
                    if (choice.separator) {
                        HorizontalDivider()
                    } else {
                        val isSelected = choice.id in selectedIds.value
                        ListItem(
                            headlineContent = { Text(choice.label) },
                            leadingContent = {
                                if (isMultiple) {
                                    Checkbox(checked = isSelected, onCheckedChange = null)
                                } else {
                                    RadioButton(selected = isSelected, onClick = null)
                                }
                            },
                            modifier = Modifier.clickable(enabled = !choice.disabled) {
                                if (isMultiple) {
                                    selectedIds.value = if (isSelected) {
                                        selectedIds.value - choice.id
                                    } else {
                                        selectedIds.value + choice.id
                                    }
                                } else {
                                    onConfirmSingle(choice)
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isMultiple) {
                TextButton(
                    onClick = {
                        val selected = flatChoices
                            .filter { it.id in selectedIds.value }
                            .toTypedArray()
                        onConfirmMultiple(selected)
                    },
                ) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

private fun flattenChoices(
    choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
): List<GeckoSession.PromptDelegate.ChoicePrompt.Choice> {
    return choices.flatMap { choice ->
        if (choice.items != null) {
            choice.items!!.toList()
        } else {
            listOf(choice)
        }
    }
}

/**
 * クリップボードに画像の content:// URI が含まれている場合にそれを返す。
 * 画像でない場合や URI が存在しない場合は null を返す。
 */
private fun getClipboardImageUri(context: Context): Uri? {
    val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipManager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0) ?: return null
    val uri = item.uri ?: return null
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: return null
    return if (mimeType.startsWith("image/")) uri else null
}

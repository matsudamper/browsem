package net.matsudamper.browser

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.ui.common.BrowserTheme
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoSession

@Composable
internal fun BrowserTabDialogLayer(
    state: BrowserTabScreenState,
    dialogState: PromptDialogState,
    enableTabUi: Boolean,
    onOpenNewTabRequest: (url: String, referrerUrl: String?) -> Unit,
    customTabMode: Boolean = false,
    onOpenFile: ((String) -> Unit)? = null,
) {
    state.contextMenuState?.let { menu ->
        ContextMenuDialog(
            menu = menu,
            enableTabUi = enableTabUi,
            customTabMode = customTabMode,
            // ホットリンク保護のあるサーバーで 403 にならないよう、元ページを referrer に付ける
            onOpenNewTab = { url ->
                if (!state.handleWebAppCrossDomainNavigation(url)) {
                    onOpenNewTabRequest(url, state.currentPageUrl)
                }
                state.dismissContextMenu()
            },
            onOpenUrl = { url ->
                if (!state.handleWebAppCrossDomainNavigation(url)) {
                    state.openUrlWithReferrer(url)
                }
                state.dismissContextMenu()
            },
            onCopyLink = { url -> state.copyLinkUrl(url) },
            onDownloadImage = { url -> state.downloadImage(url) },
            onDismiss = state::dismissContextMenu,
        )
    }

    // サイトごとのマイク許可確認ダイアログ。
    // OS の権限ダイアログより前に表示され、選択はサイト設定として永続化される。
    state.microphonePermissionDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = state::dismissMicrophonePermissionDialog,
            title = { Text("マイクの使用許可") },
            text = {
                Text(
                    "${dialog.host} がマイクの使用を求めています。" +
                        "この設定はメニューの「サイトの設定」から変更できます。",
                )
            },
            confirmButton = {
                TextButton(onClick = { state.confirmMicrophonePermissionDialog(true) }) {
                    Text("許可")
                }
            },
            dismissButton = {
                TextButton(onClick = { state.confirmMicrophonePermissionDialog(false) }) {
                    Text("ブロック")
                }
            },
        )
    }

    // サイトごとの自動再生（音声付きメディア）許可確認ダイアログ。
    // 「許可」「却下」はサイト設定として永続化され、次回以降は確認せずに適用される。
    // 「今回のみ許可」とダイアログを閉じただけの場合は永続化せず、次回も確認する。
    state.autoplayPermissionDialog?.let { dialog ->
        AutoplayPermissionDialog(
            host = dialog.host,
            onAllow = {
                state.confirmAutoplayPermissionDialog(
                    BrowserTabScreenState.AutoplayPermissionChoice.Allow,
                )
            },
            onAllowOnce = {
                state.confirmAutoplayPermissionDialog(
                    BrowserTabScreenState.AutoplayPermissionChoice.AllowOnce,
                )
            },
            onDeny = {
                state.confirmAutoplayPermissionDialog(
                    BrowserTabScreenState.AutoplayPermissionChoice.Deny,
                )
            },
            onDismiss = state::dismissAutoplayPermissionDialog,
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

    state.duplicateDownloadState?.let { duplicateState ->
        DuplicateDownloadDialog(
            state = duplicateState,
            onConfirm = state::confirmDuplicateDownload,
            onDismiss = state::dismissDuplicateDownload,
            onOpenFile = onOpenFile,
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
        when (prompt.type) {
            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE -> {
                DateInputDialog(
                    defaultValue = prompt.defaultValue,
                    onConfirm = dialogState::confirmDateTimePrompt,
                    onDismiss = dialogState::dismissDateTimePrompt,
                )
            }
            GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME -> {
                TimeInputDialog(
                    defaultValue = prompt.defaultValue,
                    onConfirm = dialogState::confirmDateTimePrompt,
                    onDismiss = dialogState::dismissDateTimePrompt,
                )
            }
            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> {
                DateTimeLocalInputDialog(
                    defaultValue = prompt.defaultValue,
                    onConfirm = dialogState::confirmDateTimePrompt,
                    onDismiss = dialogState::dismissDateTimePrompt,
                )
            }
            else -> {
                // MONTH, WEEK: テキスト入力で対応
                var dateTimeText by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "") }
                val (title, hint) = when (prompt.type) {
                    GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH -> "年月を選択" to "YYYY-MM"
                    GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK -> "週を選択" to "YYYY-Www"
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
        }
    }

    dialogState.pendingAuthPrompt?.let { prompt ->
        AuthPromptDialog(
            prompt = prompt,
            onConfirm = { username, password ->
                if (username != null) {
                    dialogState.confirmAuthPrompt(username, password)
                } else {
                    dialogState.confirmAuthPromptPasswordOnly(password)
                }
            },
            onDismiss = dialogState::dismissAuthPrompt,
        )
    }

    dialogState.pendingBeforeUnloadPrompt?.let {
        AlertDialog(
            onDismissRequest = dialogState::dismissBeforeUnloadPrompt,
            title = { Text("ページを離れますか？") },
            text = { Text("入力した内容が保存されない可能性があります。") },
            confirmButton = {
                TextButton(onClick = { dialogState.confirmBeforeUnloadPrompt(true) }) {
                    Text("離れる")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.confirmBeforeUnloadPrompt(false) }) {
                    Text("このページに留まる")
                }
            },
        )
    }

    dialogState.pendingRepostConfirmPrompt?.let {
        AlertDialog(
            onDismissRequest = dialogState::dismissRepostConfirmPrompt,
            title = { Text("フォームデータを再送信しますか？") },
            text = { Text("このページを表示するにはフォームデータを再送信する必要があります。") },
            confirmButton = {
                TextButton(onClick = { dialogState.confirmRepostConfirmPrompt(true) }) {
                    Text("再送信")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.confirmRepostConfirmPrompt(false) }) {
                    Text("キャンセル")
                }
            },
        )
    }

    dialogState.pendingAddressSelectPrompt?.let { prompt ->
        AddressSelectDialog(
            options = prompt.options.toList(),
            onSelect = dialogState::confirmAddressSelect,
            onDismiss = dialogState::dismissAddressSelect,
        )
    }

    dialogState.pendingAddressSaveAddress?.let { address ->
        AddressSaveDialog(
            address = address,
            onSave = dialogState::confirmAddressSave,
            onDismiss = dialogState::dismissAddressSave,
        )
    }
}

@Composable
private fun AddressSelectDialog(
    options: List<Autocomplete.AddressSelectOption>,
    onSelect: (Autocomplete.AddressSelectOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(BrowserTabDialogLayerTestTags.AddressSelectDialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("住所を選択") },
        text = {
            LazyColumn {
                items(options) { option ->
                    val address = option.value
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "${address.familyName} ${address.givenName}".trim()
                                    .ifEmpty { address.organization },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = buildAddressDisplayText(address),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier = Modifier.clickable { onSelect(option) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (option !== options.last()) HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun AddressSaveDialog(
    address: Autocomplete.Address,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = "${address.familyName} ${address.givenName}".trim()
    AlertDialog(
        modifier = Modifier.testTag(BrowserTabDialogLayerTestTags.AddressSaveDialog.testTag),
        onDismissRequest = onDismiss,
        title = { Text("住所を保存しますか？") },
        text = {
            Column {
                if (name.isNotEmpty()) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(buildAddressDisplayText(address), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                modifier = Modifier.testTag(BrowserTabDialogLayerTestTags.AddressSaveConfirmButton.testTag),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("保存しない") } },
    )
}

sealed interface BrowserTabDialogLayerTestTags {
    val id: String
    val testTag get() = "${BrowserTabDialogLayerTestTags::class.java.name}#$id"

    data object AddressSelectDialog : BrowserTabDialogLayerTestTags { override val id = "address_select_dialog" }
    data object AddressSaveDialog : BrowserTabDialogLayerTestTags { override val id = "address_save_dialog" }
    data object AddressSaveConfirmButton : BrowserTabDialogLayerTestTags { override val id = "address_save_confirm_button" }
}

private fun buildAddressDisplayText(address: Autocomplete.Address): String = buildList {
    if (address.postalCode.isNotEmpty()) add("〒${address.postalCode}")
    if (address.addressLevel1.isNotEmpty()) add(address.addressLevel1)
    if (address.addressLevel2.isNotEmpty()) add(address.addressLevel2)
    if (address.addressLevel3.isNotEmpty()) add(address.addressLevel3)
    if (address.streetAddress.isNotEmpty()) add(address.streetAddress)
    if (address.tel.isNotEmpty()) add(address.tel)
    if (address.email.isNotEmpty()) add(address.email)
}.joinToString(" ")

@Composable
private fun AutoplayPermissionDialog(
    host: String,
    onAllow: () -> Unit,
    onAllowOnce: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音声の自動再生") },
        text = {
            Text(
                "$host が音声付きメディアの自動再生を求めています。" +
                    "却下しても再生ボタンを押せば再生できます。" +
                    "「今回のみ許可」を選ぶと設定は保存されず、次回も確認します。" +
                    "この設定はメニューの「サイトの設定」から変更できます。",
            )
        },
        // 選択肢が3つあり、日本語ラベルは横並びだと狭い画面で見切れるため縦に並べる
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                // ダイアログは MainActivity とは別のコンポジションルートになり、
                // MainActivity で設定した testTagsAsResourceId が届かないためここでも設定する
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            ) {
                TextButton(
                    onClick = onAllow,
                    modifier = Modifier.testTag(AutoplayPermissionDialogTestTags.Allow.testTag),
                ) {
                    Text("許可")
                }
                TextButton(
                    onClick = onAllowOnce,
                    modifier = Modifier.testTag(AutoplayPermissionDialogTestTags.AllowOnce.testTag),
                ) {
                    Text("今回のみ許可")
                }
                TextButton(
                    onClick = onDeny,
                    modifier = Modifier.testTag(AutoplayPermissionDialogTestTags.Deny.testTag),
                ) {
                    Text("却下")
                }
            }
        },
    )
}

sealed interface AutoplayPermissionDialogTestTags {
    val id: String
    val testTag get() = "${AutoplayPermissionDialogTestTags::class.java.name}#$id"

    object Allow : AutoplayPermissionDialogTestTags {
        override val id = "allow"
    }

    object AllowOnce : AutoplayPermissionDialogTestTags {
        override val id = "allow_once"
    }

    object Deny : AutoplayPermissionDialogTestTags {
        override val id = "deny"
    }
}

@Composable
private fun ContextMenuDialog(
    menu: BrowserTabScreenState.ContextMenuState,
    enableTabUi: Boolean,
    customTabMode: Boolean,
    onOpenNewTab: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onDownloadImage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (menu) {
        is BrowserTabScreenState.ContextMenuState.Link -> "リンク"
        is BrowserTabScreenState.ContextMenuState.Image -> "画像"
        is BrowserTabScreenState.ContextMenuState.LinkWithImage -> "リンクと画像"
    }
    val bodyText = when (menu) {
        is BrowserTabScreenState.ContextMenuState.Link -> menu.url
        is BrowserTabScreenState.ContextMenuState.Image -> menu.srcUrl
        is BrowserTabScreenState.ContextMenuState.LinkWithImage -> menu.url
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Text(
                text = bodyText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // ボタンを縦に並べたいので confirm は空にして dismissButton スロットに集約する
        confirmButton = {},
        dismissButton = {
            // 片手で持っていても押しやすいように全幅にし、テキストを中央寄せにする
            Column(modifier = Modifier.fillMaxWidth()) {
                when (menu) {
                    is BrowserTabScreenState.ContextMenuState.Link -> {
                        LinkActionButtons(
                            url = menu.url,
                            enableTabUi = enableTabUi,
                            customTabMode = customTabMode,
                            onOpenNewTab = onOpenNewTab,
                            onOpenUrl = onOpenUrl,
                            onCopyLink = onCopyLink,
                        )
                    }
                    is BrowserTabScreenState.ContextMenuState.Image -> {
                        ImageActionButtons(
                            srcUrl = menu.srcUrl,
                            enableTabUi = enableTabUi,
                            onOpenNewTab = onOpenNewTab,
                            onOpenUrl = onOpenUrl,
                            onDownloadImage = onDownloadImage,
                        )
                    }
                    is BrowserTabScreenState.ContextMenuState.LinkWithImage -> {
                        LinkActionButtons(
                            url = menu.url,
                            enableTabUi = enableTabUi,
                            customTabMode = customTabMode,
                            onOpenNewTab = onOpenNewTab,
                            onOpenUrl = onOpenUrl,
                            onCopyLink = onCopyLink,
                        )
                        if (enableTabUi) {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpenNewTab(menu.imageSrcUrl) },
                            ) {
                                Text(text = "画像を新しいタブで開く")
                            }
                        } else {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpenUrl(menu.imageSrcUrl) },
                            ) {
                                Text(text = "画像を開く")
                            }
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onDownloadImage(menu.imageSrcUrl) },
                        ) {
                            Text(text = "画像をダウンロード")
                        }
                    }
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss,
                ) {
                    Text(text = "キャンセル")
                }
            }
        },
    )
}

@Composable
private fun ImageActionButtons(
    srcUrl: String,
    enableTabUi: Boolean,
    onOpenNewTab: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDownloadImage: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (enableTabUi) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenNewTab(srcUrl) },
            ) {
                Text(text = "新しいタブで開く")
            }
        } else {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenUrl(srcUrl) },
            ) {
                Text(text = "開く")
            }
        }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDownloadImage(srcUrl) },
        ) {
            Text(text = "ダウンロード")
        }
    }
}

@Composable
private fun LinkActionButtons(
    url: String,
    enableTabUi: Boolean,
    customTabMode: Boolean,
    onOpenNewTab: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyLink: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (enableTabUi || customTabMode) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenNewTab(url) },
            ) {
                Text(text = "新しいタブで開く")
            }
        }
        if (!enableTabUi) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenUrl(url) },
            ) {
                Text(text = "開く")
            }
        }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onCopyLink(url) },
        ) {
            Text(text = "URLをコピー")
        }
    }
}

@Composable
private fun AuthPromptDialog(
    prompt: GeckoSession.PromptDelegate.AuthPrompt,
    onConfirm: (username: String?, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isPasswordOnly = (prompt.authOptions.flags and
        GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
    var username by remember(prompt) {
        mutableStateOf(prompt.authOptions.username.orEmpty())
    }
    var password by remember(prompt) {
        mutableStateOf(prompt.authOptions.password.orEmpty())
    }
    var passwordVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(prompt.title?.takeIf { it.isNotEmpty() } ?: "認証が必要です")
        },
        text = {
            Column {
                prompt.message?.takeIf { it.isNotEmpty() }?.let {
                    Text(it)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!isPasswordOnly) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("ユーザー名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painter = if (passwordVisible) {
                                    painterResource(R.drawable.ic_visibility_off)
                                } else {
                                    painterResource(R.drawable.ic_visibility)
                                },
                                contentDescription = if (passwordVisible) "パスワードを隠す" else "パスワードを表示",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        if (isPasswordOnly) null else username,
                        password,
                    )
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateInputDialog(
    defaultValue: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDateMillis = remember(defaultValue) { parseDateToMillis(defaultValue) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = datePickerState.selectedDateMillis ?: return@TextButton
                    onConfirm(formatDateMillis(millis))
                },
                enabled = datePickerState.selectedDateMillis != null,
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInputDialog(
    defaultValue: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialHour, initialMinute) = remember(defaultValue) { parseTimeToHourMinute(defaultValue) }
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("時刻を選択") },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(formatHourMinute(timePickerState.hour, timePickerState.minute))
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

private enum class DateTimeLocalStep { DATE, TIME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeLocalInputDialog(
    defaultValue: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialDateMillis, initialHour, initialMinute) = remember(defaultValue) {
        parseDateTimeLocal(defaultValue)
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis,
    )
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
    )
    var step by remember { mutableStateOf(DateTimeLocalStep.DATE) }

    when (step) {
        DateTimeLocalStep.DATE -> {
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        onClick = { step = DateTimeLocalStep.TIME },
                        enabled = datePickerState.selectedDateMillis != null,
                    ) {
                        Text("次へ")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("キャンセル")
                    }
                },
            ) {
                DatePicker(state = datePickerState)
            }
        }
        DateTimeLocalStep.TIME -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("時刻を選択") },
                text = {
                    TimePicker(state = timePickerState)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val dateMillis = datePickerState.selectedDateMillis ?: return@TextButton
                            val dateStr = formatDateMillis(dateMillis)
                            val timeStr = formatHourMinute(timePickerState.hour, timePickerState.minute)
                            onConfirm("${dateStr}T${timeStr}")
                        },
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = DateTimeLocalStep.DATE }) {
                        Text("戻る")
                    }
                },
            )
        }
    }
}

private data class DateTimeLocalParsed(
    val dateMillis: Long?,
    val hour: Int,
    val minute: Int,
)

/** "YYYY-MM-DD" 形式の文字列を UTC ミリ秒に変換する */
private fun parseDateToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val parts = value.split("-")
        if (parts.size < 3) return null
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val day = parts[2].toInt()
        // isLenient = false で無効な日付（例: 2月30日）を例外として検出する
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.isLenient = false
        calendar.set(year, month - 1, day, 0, 0, 0)  // Calendar の月は 0 始まり
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.timeInMillis  // isLenient = false の場合、無効な日付で例外が発生する
    }.getOrNull()
}

/** UTC ミリ秒を "YYYY-MM-DD" 形式に変換する */
private fun formatDateMillis(millis: Long): String {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.timeInMillis = millis
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
}

/** "HH:MM" または "HH:MM:SS" 形式の文字列を (hour, minute) に変換する */
private fun parseTimeToHourMinute(value: String?): Pair<Int, Int> {
    if (value.isNullOrBlank()) return 0 to 0
    return runCatching {
        val parts = value.split(":")
        val hour = parts[0].toInt().coerceIn(0, 23)
        val minute = if (parts.size > 1) parts[1].toInt().coerceIn(0, 59) else 0
        hour to minute
    }.getOrElse { 0 to 0 }
}

/** hour と minute を "HH:MM" 形式に変換する */
private fun formatHourMinute(hour: Int, minute: Int): String {
    return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
}

/** "YYYY-MM-DDTHH:MM" 形式の文字列を解析する */
private fun parseDateTimeLocal(value: String?): DateTimeLocalParsed {
    if (value.isNullOrBlank()) return DateTimeLocalParsed(null, 0, 0)
    return runCatching {
        val parts = value.split("T")
        val dateMillis = if (parts.isNotEmpty()) parseDateToMillis(parts[0]) else null
        val (hour, minute) = if (parts.size > 1) parseTimeToHourMinute(parts[1]) else 0 to 0
        DateTimeLocalParsed(dateMillis, hour, minute)
    }.getOrElse { DateTimeLocalParsed(null, 0, 0) }
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
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                            ),
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

@Composable
private fun DuplicateDownloadDialog(
    state: BrowserTabScreenState.DuplicateDownloadState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFile: ((String) -> Unit)?,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ダウンロードの重複") },
        text = {
            Column {
                state.existingDownloads.forEach { entry ->
                    val displayName = entry.fileName.ifEmpty { "（ファイル名未取得）" }
                    val fileUri = entry.fileUri
                    Text(
                        text = buildAnnotatedString {
                            if (onOpenFile != null && fileUri != null) {
                                withLink(
                                    LinkAnnotation.Clickable("open_file") {
                                        onDismiss()
                                        onOpenFile(fileUri)
                                    },
                                ) {
                                    withStyle(
                                        SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    ) {
                                        append(displayName)
                                    }
                                }
                            } else {
                                append(displayName)
                            }
                            append(" は既に存在します")
                        },
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("続行しますか？")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("ダウンロード")
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

@Preview(name = "DuplicateDownloadDialog")
@Composable
private fun PreviewDuplicateDownloadDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DuplicateDownloadDialog(
            state = BrowserTabScreenState.DuplicateDownloadState(
                url = "https://example.com/file.zip",
                existingDownloads = listOf(
                    BrowserTabScreenState.DuplicateDownloadEntry(
                        fileName = "file.zip",
                        status = DownloadRecordStatus.SUCCEEDED,
                        fileUri = "content://media/external/downloads/123",
                    ),
                    BrowserTabScreenState.DuplicateDownloadEntry(
                        fileName = "file.zip",
                        status = DownloadRecordStatus.RUNNING,
                        fileUri = null,
                    ),
                ),
                onConfirm = {},
            ),
            onConfirm = {},
            onDismiss = {},
            onOpenFile = {},
        )
    }
}

@Preview(name = "AutoplayPermissionDialog")
@Composable
private fun PreviewAutoplayPermissionDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        AutoplayPermissionDialog(
            host = "www.example.com",
            onAllow = {},
            onAllowOnce = {},
            onDeny = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "AddressSaveDialog")
@Composable
private fun PreviewAddressSaveDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        AddressSaveDialog(
            address = Autocomplete.Address.Builder()
                .familyName("山田")
                .givenName("太郎")
                .postalCode("100-0001")
                .addressLevel1("東京都")
                .addressLevel2("千代田区")
                .streetAddress("千代田1-1")
                .tel("090-1234-5678")
                .email("taro@example.com")
                .build(),
            onSave = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "ContextMenuDialog")
@Composable
private fun PreviewContextMenuDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        ContextMenuDialog(
            menu = BrowserTabScreenState.ContextMenuState.LinkWithImage(
                url = "https://example.com/very/long/link/path/page.html",
                imageSrcUrl = "https://example.com/image.png",
            ),
            enableTabUi = true,
            customTabMode = false,
            onOpenNewTab = {},
            onOpenUrl = {},
            onCopyLink = {},
            onDownloadImage = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "DateInputDialog")
@Composable
private fun PreviewDateInputDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        DateInputDialog(
            defaultValue = "2024-06-15",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "TimeInputDialog")
@Composable
private fun PreviewTimeInputDialog() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        TimeInputDialog(
            defaultValue = "14:30",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

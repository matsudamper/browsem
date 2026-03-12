package net.matsudamper.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.media.MediaWebExtension
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GeckoBrowserTab(
    browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    browserSessionController: BrowserSessionController,
    modifier: Modifier = Modifier,
    tabCount: Int,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
    onOpenSettings: () -> Unit,
    onOpenTabs: () -> Unit,
    enableTabUi: Boolean = true,
    showInstallExtensionItem: Boolean = true,
    enableBackNavigation: Boolean = true,
    customTabMode: Boolean = false,
    onCloseCustomTab: (() -> Unit)? = null,
    onOpenInBrowser: ((String) -> Unit)? = null,
    onOpenNewSessionRequest: (String) -> GeckoSession,
    onCloseTab: (() -> Unit)? = null,
    onToolbarHorizontalDrag: (Float) -> Unit = {},
    onToolbarDragEnd: () -> Unit = {},
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
    historySuggestions: List<net.matsudamper.browser.data.history.HistoryEntry> = emptyList(),
    onUrlInputChanged: ((String) -> Unit)? = null,
) {
    val state = rememberBrowserTabScreenState(
        browserTab = browserTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
        onHistoryRecord = onHistoryRecord,
        onHistoryTitleUpdate = onHistoryTitleUpdate,
    )
    val dialogState = state.promptDialogState
    val session = state.session
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isImeVisible = WindowInsets.isImeVisible
    var imeWasVisibleDuringUrlFocus by remember { mutableStateOf(false) }
    var urlBarFocusStartedAtMs by remember { mutableStateOf(0L) }
    var geckoView: GeckoView? by remember { mutableStateOf(null) }

    // 不安定なラムダキーによる DisposableEffect の再実行を防ぐ
    val currentOnCloseTab by rememberUpdatedState(onCloseTab)
    val currentOnDesktopNotificationPermissionRequest by rememberUpdatedState(onDesktopNotificationPermissionRequest)
    val currentOnOpenNewSessionRequest by rememberUpdatedState(onOpenNewSessionRequest)
    val closeUrlInput: (Boolean) -> Unit = { restoreCurrentUrl ->
        state.isUrlInputFocused = false
        if (restoreCurrentUrl) {
            state.restoreCurrentPageUrlToInput()
        }
        imeWasVisibleDuringUrlFocus = false
        keyboardController?.hide()
        runCatching { session.setFocused(true) }
        geckoView?.requestFocus()
    }

    // Sync title/url changes to BrowserTab for persistence
    LaunchedEffect(state) {
        snapshotFlow { state.currentPageTitle }
            .collectLatest { state.syncTitleToTab() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentPageUrl }
            .collectLatest { state.syncUrlToTab() }
    }

    // URLバー入力変更時にサジェスト検索を発火
    LaunchedEffect(state, onUrlInputChanged) {
        snapshotFlow { state.urlInput to state.isUrlInputFocused }
            .collectLatest { (input, focused) ->
                if (focused) {
                    onUrlInputChanged?.invoke(input)
                }
            }
    }

    // Lifecycle observer for tab preview capture
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.flushSessionState()
                geckoView?.also {
                    state.captureTabPreview(it)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // theme-color WebExtensionのコールバック登録
    DisposableEffect(session, state, themeColorExtension) {
        themeColorExtension.registerSession(session) { color, reportedUrl ->
            if (!isThemeColorForCurrentPage(state.currentPageUrl, reportedUrl)) {
                return@registerSession
            }
            state.toolbarColor = color
        }
        onDispose {
            themeColorExtension.unregisterSession(session)
        }
    }

    DisposableEffect(session, mediaWebExtension) {
        mediaWebExtension.registerSession(session)
        onDispose {
            mediaWebExtension.unregisterSession(session)
        }
    }

    DisposableEffect(session, state, browserTab) {
        val permissionDelegate = state.createPermissionDelegate { uri ->
            currentOnDesktopNotificationPermissionRequest(uri)
        }
        val navigationDelegate = state.createNavigationDelegate { uri ->
            currentOnOpenNewSessionRequest(uri)
        }
        val contentDelegate = state.createContentDelegate(
            onClose = { currentOnCloseTab?.invoke() },
        )
        val progressDelegate = state.createProgressDelegate()
        val translationsDelegate = state.createTranslationsDelegate()
        val promptDelegate = dialogState.createPromptDelegate()

        session.permissionDelegate = permissionDelegate
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.progressDelegate = progressDelegate
        session.translationsSessionDelegate = translationsDelegate
        session.promptDelegate = promptDelegate

        browserSessionController.restoreSession(browserTab)

        onDispose {
            session.permissionDelegate = null
            session.navigationDelegate = null
            session.contentDelegate = null
            session.progressDelegate = null
            session.translationsSessionDelegate = null
            session.promptDelegate = null
        }
    }

    // メディアセッションデリゲートは不安定なラムダキーの影響を受けないよう独立して管理
    DisposableEffect(session, state, mediaWebExtension) {
        val mediaSessionDelegate = state.createMediaSessionDelegate(mediaWebExtension)
        session.mediaSessionDelegate = mediaSessionDelegate
        onDispose {
            if (session.mediaSessionDelegate === mediaSessionDelegate) {
                session.mediaSessionDelegate = null
            }
        }
    }

    // Attach scroll delegate
    DisposableEffect(session, state) {
        val scrollDelegate = state.createScrollDelegate()
        session.scrollDelegate = scrollDelegate
        onDispose {
            if (session.scrollDelegate === scrollDelegate) session.scrollDelegate = null
        }
    }

    // Back handlers
    BackHandler(enabled = state.showFindInPage) { state.closeFindInPage() }
    BackHandler(enabled = enableBackNavigation && state.canGoBack && !state.isUrlInputFocused) {
        state.onGoBack()
    }
    BackHandler(enabled = state.isUrlInputFocused) { closeUrlInput(true) }

    // IME visibility tracking:
    // URLバーにフォーカスした直後はIMEがまだ非表示のことがあるため、
    // 一度でもIME表示を確認した後の「非表示化」のみをフォーカス解除トリガーにする。
    LaunchedEffect(state.isUrlInputFocused, isImeVisible) {
        if (!state.isUrlInputFocused) {
            imeWasVisibleDuringUrlFocus = false
            return@LaunchedEffect
        }
        if (isImeVisible) {
            imeWasVisibleDuringUrlFocus = true
            return@LaunchedEffect
        }
        val inGracePeriod = SystemClock.elapsedRealtime() - urlBarFocusStartedAtMs <
            URL_BAR_IME_HIDE_GRACE_MS
        if (imeWasVisibleDuringUrlFocus && !inGracePeriod) {
            closeUrlInput(true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        if (state.showFindInPage) {
            FindInPageBar(
                query = state.findQuery,
                matchCurrent = state.findMatchCurrent,
                matchTotal = state.findMatchTotal,
                onQueryChange = state::onFindQueryChange,
                onNext = state::findNext,
                onPrevious = state::findPrevious,
                onClose = state::closeFindInPage,
            )
        } else {
            if (customTabMode) {
                CustomTabToolbar(
                    title = state.currentPageTitle.ifBlank { "ページ" },
                    url = state.currentPageUrl,
                    onClose = { onCloseCustomTab?.invoke() ?: onCloseTab?.invoke() },
                    onRefresh = state::onRefresh,
                    onShare = state::sharePage,
                    onOpenInBrowser = onOpenInBrowser?.let { callback ->
                        { callback(state.currentPageUrl) }
                    },
                    toolbarColor = state.toolbarColor,
                )
            } else {
                BrowserToolBar(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.urlInput,
                    onValueChange = { state.urlInput = it },
                    onSubmit = { rawInput ->
                        state.onUrlSubmit(rawInput)
                        closeUrlInput(false)
                    },
                    isFocused = state.isUrlInputFocused,
                    onFocusChanged = { hasFocus ->
                        if (hasFocus) {
                            urlBarFocusStartedAtMs = SystemClock.elapsedRealtime()
                            if (!state.isUrlInputFocused) {
                                state.urlInput = ""
                            }
                            runCatching { session.setFocused(false) }
                            geckoView?.clearFocus()
                            keyboardController?.show()
                        } else {
                            state.restoreCurrentPageUrlToInput()
                        }
                        state.isUrlInputFocused = hasFocus
                    },
                    showInstallExtensionItem = showInstallExtensionItem && state.showInstallExtensionItem,
                    onInstallExtension = { onInstallExtensionRequest(state.currentPageUrl) },
                    onOpenSettings = onOpenSettings,
                    onShare = state::sharePage,
                    tabCount = tabCount,
                    showTabActions = enableTabUi,
                    onOpenTabs = {
                        if (enableTabUi) {
                            geckoView?.also {
                                state.flushAndCaptureForTabSwitch(it)
                            }
                            onOpenTabs()
                        }
                    },
                    isPcMode = state.isPcMode,
                    onPcModeToggle = state::togglePcMode,
                    onFindInPage = state::openFindInPage,
                    toolbarColor = state.toolbarColor,
                    onHome = state::onHome,
                    onForward = state::onGoForward,
                    canGoForward = state.canGoForward,
                    onRefresh = state::onRefresh,
                    onTranslatePage = { state.onTranslate(translationProvider) },
                    onHorizontalDrag = onToolbarHorizontalDrag,
                    onHorizontalDragEnd = onToolbarDragEnd,
                )
            }
            TranslationStatusBar(
                state = state.translationState,
                onRevert = state::onRevertTranslation,
                onDismissError = state::onDismissTranslationError,
            )
        }

        val latestOnRefresh by rememberUpdatedState { state.onRefreshFromSwipe() }
        val id = rememberSaveable { View.generateViewId() }
        Box(
            modifier = Modifier
                .weight(1f)
                .testTag(TEST_TAG_GECKO_CONTAINER),
        ) {
            BrowserContentHost(
                modifier = Modifier.fillMaxSize(),
                state = state,
                id = id,
                session = session,
                latestOnRefresh = latestOnRefresh,
                browserTab = browserTab,
                updateGeckoView = {
                    geckoView = it
                }
            )

            state.pageLoadError?.let { pageLoadError ->
                PageLoadErrorOverlay(
                    pageLoadError = pageLoadError,
                    onRetry = state::retryPageLoad,
                )
            }

            // URLバーフォーカス中にサジェストを表示
            if (
                !state.showFindInPage &&
                state.isUrlInputFocused &&
                (historySuggestions.isNotEmpty() || state.currentPageUrl.isNotBlank())
            ) {
                HistorySuggestionList(
                    currentPageUrl = state.currentPageUrl,
                    suggestions = historySuggestions,
                    onSuggestionClick = { entry ->
                        state.onUrlSubmit(entry.url)
                        closeUrlInput(false)
                    },
                    onCopyCurrentUrl = state::copyCurrentPageUrl,
                    onRestoreCurrentUrl = state::restoreCurrentPageUrlToInput,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TEST_TAG_HISTORY_SUGGESTION_LIST)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }

        // Image context menu dialog
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

        // Link context menu dialog
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
                                    currentOnOpenNewSessionRequest(linkUrl)
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

        // ファイルダウンロード確認ダイアログ
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

        // Alert prompt dialog
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

        // Button prompt dialog (window.confirm())
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

        // Text prompt dialog (window.prompt())
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

        // Choice prompt dialog (<select> elements)
        dialogState.pendingChoicePrompt?.let { prompt ->
            ChoicePromptDialog(
                prompt = prompt,
                onDismiss = dialogState::dismissChoicePrompt,
                onConfirmSingle = dialogState::confirmChoicePromptSingle,
                onConfirmMultiple = dialogState::confirmChoicePromptMultiple,
            )
        }

        // Color prompt dialog (<input type="color">)
        dialogState.pendingColorPrompt?.let { prompt ->
            var colorText by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "#000000") }
            val parsedColor = remember(colorText) {
                runCatching {
                    androidx.compose.ui.graphics.Color(colorText.toColorInt())
                }.getOrNull()
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
                            Spacer(modifier = Modifier.height(8.dp))
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

        // DateTime prompt dialog (<input type="date/time/...">)
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
    }
}

@Composable
private fun CustomTabToolbar(
    title: String,
    url: String,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    toolbarColor: Color?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val resolvedToolbarColor = toolbarColor ?: MaterialTheme.colorScheme.primaryContainer
    val toolbarContentColor = if (resolvedToolbarColor.luminance() >= 0.5f) Color.Black else Color.White
    val toolbarSecondaryContentColor = toolbarContentColor.copy(alpha = 0.72f)

    Surface(
        color = resolvedToolbarColor,
        contentColor = toolbarContentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.close_24dp),
                    contentDescription = "閉じる",
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = toolbarSecondaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert_24dp),
                        contentDescription = "メニュー",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    onOpenInBrowser?.let { openInBrowser ->
                        DropdownMenuItem(
                            text = { Text("ブラウザで開く") },
                            onClick = {
                                menuExpanded = false
                                openInBrowser()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("共有") },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("更新") },
                        onClick = {
                            menuExpanded = false
                            onRefresh()
                        },
                    )
                }
            }
        }
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
                TextButton(onClick = {
                    val selected = flatChoices
                        .filter { it.id in selectedIds.value }
                        .toTypedArray()
                    onConfirmMultiple(selected)
                }) {
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
        if (choice.items != null) choice.items!!.toList() else listOf(choice)
    }
}
private const val URL_BAR_IME_HIDE_GRACE_MS = 700L

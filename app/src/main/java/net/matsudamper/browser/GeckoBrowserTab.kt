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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    BackHandler(enabled = state.canGoBack && !state.isUrlInputFocused) { state.onGoBack() }
    BackHandler(enabled = state.isUrlInputFocused) {
        state.isUrlInputFocused = false
        state.urlInput = state.currentPageUrl
        imeWasVisibleDuringUrlFocus = false
        keyboardController?.hide()
        runCatching { session.setFocused(true) }
        geckoView?.requestFocus()
    }

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
            state.isUrlInputFocused = false
            imeWasVisibleDuringUrlFocus = false
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
            BrowserToolBar(
                modifier = Modifier.fillMaxWidth(),
                value = state.urlInput,
                onValueChange = { state.urlInput = it },
                onSubmit = { rawInput ->
                    state.onUrlSubmit(rawInput)
                    state.isUrlInputFocused = false
                    keyboardController?.hide()
                },
                isFocused = state.isUrlInputFocused,
                onFocusChanged = { hasFocus ->
                    if (hasFocus) {
                        urlBarFocusStartedAtMs = SystemClock.elapsedRealtime()
                        runCatching { session.setFocused(false) }
                        geckoView?.clearFocus()
                        keyboardController?.show()
                    } else {
                        state.urlInput = state.currentPageUrl
                    }
                    state.isUrlInputFocused = hasFocus
                },
                showInstallExtensionItem = state.showInstallExtensionItem,
                onInstallExtension = { onInstallExtensionRequest(state.currentPageUrl) },
                onOpenSettings = onOpenSettings,
                onShare = state::sharePage,
                tabCount = tabCount,
                onOpenTabs = {
                    geckoView?.also {
                        state.flushAndCaptureForTabSwitch(it)
                    }
                    onOpenTabs()
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
            GeckoView(
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

            // URLバーフォーカス中にサジェストを表示
            if (!state.showFindInPage && state.isUrlInputFocused && historySuggestions.isNotEmpty()) {
                HistorySuggestionList(
                    suggestions = historySuggestions,
                    onSuggestionClick = { entry ->
                        state.onUrlSubmit(entry.url)
                        state.isUrlInputFocused = false
                        keyboardController?.hide()
                    },
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
                        TextButton(
                            onClick = {
                                currentOnOpenNewSessionRequest(linkUrl)
                                state.linkContextMenuUrl = null
                            },
                        ) {
                            Text("新しいタブで開く")
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

        // サイト側でハンドリングできないページロードエラーを表示
        state.pageLoadErrorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = state::dismissPageLoadError,
                title = { Text("読み込みエラー") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = state::dismissPageLoadError) {
                        Text("閉じる")
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

private fun isThemeColorForCurrentPage(currentPageUrl: String, reportedUrl: String): Boolean {
    if (reportedUrl.isBlank()) return false
    return normalizedThemeColorUrlKey(currentPageUrl) == normalizedThemeColorUrlKey(reportedUrl)
}

private fun normalizedThemeColorUrlKey(url: String): String {
    return url
        .substringBefore("#")
        .removeSuffix("/")
}

@Composable
private fun GeckoView(
    state: BrowserTabScreenState,
    id: Int,
    session: GeckoSession,
    browserTab: BrowserTab,
    latestOnRefresh: () -> Unit,
    updateGeckoView: (GeckoView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { context ->
                SwipeRefreshLayout(context).also { swipeRefreshLayout ->
                    var swipeRefreshScrollEnabled = false
                    val gecko = GeckoView(context).also { geckoView ->
                        geckoView.id = id
                        geckoView.isNestedScrollingEnabled = true
                        geckoView.setAutofillEnabled(true)
                        geckoView.importantForAutofill =
                            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                        geckoView.setSession(session)
                        @SuppressLint("ClickableViewAccessibility")
                        geckoView.setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                swipeRefreshScrollEnabled = false
                                (view as GeckoView).onTouchEventForDetailResult(event).then { detail ->
                                    if (detail != null) {
                                        val handledResult = detail.handledResult()
                                        // コンテンツ側でのhandleを試みたが、動かないのでブラウザ側に渡している
                                        val isUnhandled = handledResult == PanZoomController.INPUT_RESULT_UNHANDLED
                                        // ブラウザ側のジェスチャーがhandleしている
                                        val isHandled = handledResult == PanZoomController.INPUT_RESULT_HANDLED
                                        // コンテンツ側でhandleしている
                                        // val isContentHandled = handledResult == PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                                        swipeRefreshScrollEnabled = isHandled || isUnhandled
                                    }
                                    GeckoResult.fromValue<Void>(null)
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }
                    updateGeckoView(gecko)
                    swipeRefreshLayout.addView(
                        gecko,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    )
                    swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
                        !swipeRefreshScrollEnabled || state.scrollY > 0
                    }
                    swipeRefreshLayout.setOnRefreshListener {
                        state.isRefreshing = true
                        latestOnRefresh()
                    }
                }
            },
            update = { swipeRefreshLayout ->
                swipeRefreshLayout.isRefreshing = state.isRefreshing
                val geckoView = swipeRefreshLayout.findViewById<GeckoView>(id)
                if (!state.isUrlInputFocused && !geckoView.isFocused) {
                    geckoView.requestFocus()
                }
            },
        )

        val previewBytes = browserTab.previewBitmap
        var previewBitmap: Bitmap? by remember(null) {
            mutableStateOf(null)
        }
        LaunchedEffect(previewBytes) {
            previewBitmap = if (previewBytes != null) {
                BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
            } else {
                null
            }
        }
        if (!state.renderReady) {
            previewBitmap?.also { bitmap ->
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopStart,
                )
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

@Composable
private fun HistorySuggestionList(
    suggestions: List<net.matsudamper.browser.data.history.HistoryEntry>,
    onSuggestionClick: (net.matsudamper.browser.data.history.HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(suggestions, key = { it.id }) { entry ->
            ListItem(
                headlineContent = {
                    Text(
                        text = entry.title.ifBlank { entry.url },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    if (entry.title.isNotBlank()) {
                        Text(
                            text = entry.url,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                modifier = Modifier.clickable { onSuggestionClick(entry) },
            )
        }
    }
}

private fun flattenChoices(
    choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
): List<GeckoSession.PromptDelegate.ChoicePrompt.Choice> {
    return choices.flatMap { choice ->
        if (choice.items != null) choice.items!!.toList() else listOf(choice)
    }
}

const val TEST_TAG_GECKO_CONTAINER = "gecko_container"
const val TEST_TAG_HISTORY_SUGGESTION_LIST = "history_suggestion_list"
private const val URL_BAR_IME_HIDE_GRACE_MS = 700L

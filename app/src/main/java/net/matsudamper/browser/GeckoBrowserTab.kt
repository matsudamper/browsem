package net.matsudamper.browser

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.TranslationProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GeckoBrowserTab(
    browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    modifier: Modifier = Modifier,
    tabCount: Int,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
    onOpenSettings: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenNewSessionRequest: (String) -> GeckoSession,
) {
    val state = rememberBrowserTabScreenState(
        browserTab = browserTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
    )
    val session = state.session
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isImeVisible = WindowInsets.isImeVisible

    // Sync title/url changes to BrowserTab for persistence
    LaunchedEffect(state) {
        snapshotFlow { state.currentPageTitle }
            .collectLatest { state.syncTitleToTab() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentPageUrl }
            .collectLatest { state.syncUrlToTab() }
    }

    // Lifecycle observer for tab preview capture
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.flushSessionState()
                val geckoView = state.geckoViewRef ?: return@LifecycleEventObserver
                state.captureTabPreview(geckoView)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Attach session delegates
    DisposableEffect(session, state) {
        val permissionDelegate =
            state.createPermissionDelegate(onDesktopNotificationPermissionRequest)
        val navigationDelegate = state.createNavigationDelegate(onOpenNewSessionRequest)
        val contentDelegate = state.createContentDelegate()
        val progressDelegate = state.createProgressDelegate()
        val translationsDelegate = state.createTranslationsDelegate()
        val promptDelegate = state.createPromptDelegate()

        session.permissionDelegate = permissionDelegate
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.progressDelegate = progressDelegate
        session.translationsSessionDelegate = translationsDelegate
        session.promptDelegate = promptDelegate

        onDispose {
            if (session.permissionDelegate === permissionDelegate) session.permissionDelegate = null
            if (session.navigationDelegate === navigationDelegate) session.navigationDelegate =
                null
            if (session.contentDelegate === contentDelegate) session.contentDelegate = null
            if (session.progressDelegate === progressDelegate) session.progressDelegate = null
            if (session.translationsSessionDelegate === translationsDelegate) session.translationsSessionDelegate =
                null
            if (session.promptDelegate === promptDelegate) session.promptDelegate = null
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
    BackHandler(enabled = state.canGoBack) { state.onGoBack() }

    // IME visibility tracking
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            state.isUrlInputFocused = false
        }
    }

    // --- UI ---
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
                    keyboardController?.hide()
                },
                isFocused = state.isUrlInputFocused,
                onFocusChanged = { hasFocus ->
                    if (!hasFocus) {
                        state.urlInput = state.currentPageUrl
                    }
                    state.isUrlInputFocused = hasFocus
                },
                onClearInput = { state.urlInput = "" },
                showInstallExtensionItem = state.showInstallExtensionItem,
                onInstallExtension = { onInstallExtensionRequest(state.currentPageUrl) },
                onOpenSettings = onOpenSettings,
                onShare = state::sharePage,
                tabCount = tabCount,
                onOpenTabs = {
                    state.flushAndCaptureForTabSwitch()
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
            )
            TranslationStatusBar(
                state = state.translationState,
                onRevert = state::onRevertTranslation,
                onDismissError = state::onDismissTranslationError,
            )
        }

        val latestOnRefresh by rememberUpdatedState { state.onRefreshFromSwipe() }
        val id = rememberSaveable { View.generateViewId() }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { context ->
                SwipeRefreshLayout(context).also { swipeRefreshLayout ->
                    val gecko = GeckoView(context).also { geckoView ->
                        geckoView.id = id
                        geckoView.isNestedScrollingEnabled = true
                        geckoView.setAutofillEnabled(true)
                        geckoView.importantForAutofill =
                            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                        geckoView.setSession(session)
                        state.geckoViewRef = geckoView
                    }
                    swipeRefreshLayout.addView(
                        gecko,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    )
                    swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
                        state.scrollY > 0
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
                geckoView.setAutofillEnabled(true)
                geckoView.importantForAutofill =
                    View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                geckoView.setSession(session)
                state.geckoViewRef = geckoView
                geckoView.isFocusable = true
                geckoView.isFocusableInTouchMode = true
                if (!state.isUrlInputFocused && !geckoView.isFocused) {
                    geckoView.requestFocus()
                }
            },
        )

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

        // Alert prompt dialog
        state.pendingAlertPrompt?.let { prompt ->
            AlertDialog(
                onDismissRequest = state::dismissAlertPrompt,
                text = { Text(prompt.message ?: "") },
                confirmButton = {
                    TextButton(onClick = state::dismissAlertPrompt) {
                        Text("OK")
                    }
                },
            )
        }

        // Button prompt dialog (window.confirm())
        state.pendingButtonPrompt?.let { prompt ->
            AlertDialog(
                onDismissRequest = state::dismissButtonPrompt,
                text = { Text(prompt.message ?: "") },
                confirmButton = {
                    TextButton(onClick = { state.confirmButtonPrompt(true) }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state.confirmButtonPrompt(false) }) {
                        Text("キャンセル")
                    }
                },
            )
        }

        // Text prompt dialog (window.prompt())
        state.pendingTextPrompt?.let { prompt ->
            var textValue by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "") }
            AlertDialog(
                onDismissRequest = state::dismissTextPrompt,
                title = prompt.message?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                text = {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { state.confirmTextPrompt(textValue) }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = state::dismissTextPrompt) {
                        Text("キャンセル")
                    }
                },
            )
        }

        // Choice prompt dialog (<select> elements)
        state.pendingChoicePrompt?.let { prompt ->
            ChoicePromptDialog(
                prompt = prompt,
                onDismiss = state::dismissChoicePrompt,
                onConfirmSingle = state::confirmChoicePromptSingle,
                onConfirmMultiple = state::confirmChoicePromptMultiple,
            )
        }

        // Color prompt dialog (<input type="color">)
        state.pendingColorPrompt?.let { prompt ->
            var colorText by remember(prompt) { mutableStateOf(prompt.defaultValue ?: "#000000") }
            val parsedColor = remember(colorText) {
                runCatching {
                    androidx.compose.ui.graphics.Color(colorText.toColorInt())
                }.getOrNull()
            }
            AlertDialog(
                onDismissRequest = state::dismissColorPrompt,
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
                        onClick = { state.confirmColorPrompt(colorText) },
                        enabled = parsedColor != null,
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = state::dismissColorPrompt) {
                        Text("キャンセル")
                    }
                },
            )
        }

        // DateTime prompt dialog (<input type="date/time/...">)
        state.pendingDateTimePrompt?.let { prompt ->
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
                onDismissRequest = state::dismissDateTimePrompt,
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
                    TextButton(onClick = { state.confirmDateTimePrompt(dateTimeText) }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = state::dismissDateTimePrompt) {
                        Text("キャンセル")
                    }
                },
            )
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
                            headlineContent = { Text(choice.label ?: "") },
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

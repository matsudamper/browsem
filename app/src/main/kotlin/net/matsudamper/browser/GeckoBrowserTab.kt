package net.matsudamper.browser

import android.app.Activity
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.media.GeckoMediaSessionDelegate
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.ui.common.resolveBrowserToolbarColors
import net.matsudamper.browser.ui.browser.SimpleViewScreen
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState
import org.koin.compose.koinInject
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GeckoBrowserTab(
    browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    modifier: Modifier = Modifier,
    tabCount: Int?,
    onInstallExtensionRequest: (String) -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenTabs: () -> Unit,
    enableTabUi: Boolean = true,
    showInstallExtensionItem: Boolean = true,
    enableBackNavigation: Boolean = true,
    customTabMode: Boolean = false,
    webAppMode: Boolean = false,
    onCloseCustomTab: (() -> Unit)? = null,
    onOpenInBrowser: ((String) -> Unit)? = null,
    onOpenNewSessionRequest: (String) -> GeckoSession,
    onOpenNewTabRequest: (String) -> Unit,
    onCloseTab: (() -> Unit)? = null,
    onToolbarHorizontalDrag: (Float) -> Unit = {},
    onToolbarDragEnd: () -> Unit = {},
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
    urlBarSuggestions: UrlBarSuggestionsUiState = UrlBarSuggestionsUiState(),
    onUrlInputChanged: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val readabilityWebExtension: ReadabilityWebExtension = koinInject()
    val findInPageWebExtension: FindInPageWebExtension = koinInject()
    // URLバーフォーカス時にクリップボードから読み取ったURL
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    // タブ履歴BottomSheetの表示状態
    var showTabHistorySheet by remember { mutableStateOf(false) }
    val state = rememberBrowserTabScreenState(
        browserTab = browserTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
        onHistoryRecord = onHistoryRecord,
        onHistoryTitleUpdate = onHistoryTitleUpdate,
        onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
    )

    // ツールバー色の輝度に応じてステータスバーアイコン色（黒/白）を動的に切り替える
    val toolbarColors = resolveBrowserToolbarColors(
        toolbarColor = state.toolbarColor,
        defaultToolbarColor = MaterialTheme.colorScheme.primaryContainer,
        isSystemDarkTheme = isSystemInDarkTheme(),
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                toolbarColors.isBrightBackground
        }
    }

    val dialogState = state.promptDialogState
    val session = state.session
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isImeVisible = WindowInsets.isImeVisible
    var imeWasVisibleDuringUrlFocus by remember { mutableStateOf(false) }
    var urlBarFocusStartedAtMs by remember { mutableStateOf(0L) }
    var geckoView: GeckoView? by remember { mutableStateOf(null) }
    var sessionReleasedOnStop by remember(session) { mutableStateOf(false) }
    var waitingForSurfaceRestore by remember(session) { mutableStateOf(false) }
    val resumeCoverColor = MaterialTheme.colorScheme.surface.toArgb()
    // ホームに追加ダイアログの表示状態
    var showAddToHomeScreenDialog by remember { mutableStateOf(false) }
    var addToHomeUrl by remember { mutableStateOf("") }
    var addToHomeTitle by remember { mutableStateOf("") }
    var addToHomeFavicon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 不安定なラムダキーによる DisposableEffect の再実行を防ぐ
    val currentOnCloseTab by rememberUpdatedState(onCloseTab)
    val currentOnOpenNewSessionRequest by rememberUpdatedState(onOpenNewSessionRequest)
    val currentOnOpenNewTabRequest by rememberUpdatedState(onOpenNewTabRequest)
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

    // URLバー入力変更時にサジェスト検索を発火
    LaunchedEffect(state, onUrlInputChanged) {
        snapshotFlow { state.urlInput to state.isUrlInputFocused }
            .collectLatest { (input, focused) ->
                if (focused) {
                    onUrlInputChanged?.invoke(input)
                }
            }
    }

    DisposableEffect(lifecycleOwner, session, resumeCoverColor) {
        fun restoreGeckoSurface(gecko: GeckoView) {
            if (!waitingForSurfaceRestore && !sessionReleasedOnStop) {
                return
            }
            if (sessionReleasedOnStop) {
                gecko.setSession(session)
                sessionReleasedOnStop = false
            }
            // GeckoView 内部の SurfaceView を明示的に再作成する。
            gecko.coverUntilFirstPaint(resumeCoverColor)
            gecko.requestNewSurface()
            gecko.requestLayout()
            gecko.invalidate()
            waitingForSurfaceRestore = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    waitingForSurfaceRestore = true
                    session.flushSessionState()
                    geckoView?.also {
                        state.captureTabPreview(it)
                        if (mediaWebExtension.shouldKeepSessionAttached(session)) {
                            sessionReleasedOnStop = false
                        } else {
                            it.releaseSession()
                            sessionReleasedOnStop = true
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    geckoView?.also(::restoreGeckoSurface)
                    // ComposeView 側のルートにも再描画要求を出す。
                    view.rootView.invalidate()
                }
                Lifecycle.Event.ON_RESUME -> {
                    geckoView?.also(::restoreGeckoSurface)
                    view.rootView.invalidate()
                }
                else -> Unit
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

    // ReadabilityWebExtension のセッション登録
    DisposableEffect(session, state, readabilityWebExtension) {
        readabilityWebExtension.registerSession(session) { article ->
            state.simpleViewArticle = article
        }
        onDispose {
            readabilityWebExtension.unregisterSession(session)
        }
    }

    // FindInPageWebExtension のセッション登録
    DisposableEffect(session, state, findInPageWebExtension) {
        findInPageWebExtension.registerSession(session) { current, total, error ->
            // 正規表現モードでないときに届いた遅延結果は無視する
            if (!state.findIsRegex) return@registerSession
            state.findMatchCurrent = current
            state.findMatchTotal = total
            state.findQueryError = if (error == "invalid_regex") "無効な正規表現です" else null
        }
        onDispose {
            findInPageWebExtension.unregisterSession(session)
        }
    }

    DisposableEffect(session, state, browserTab, mediaWebExtension) {
        browserTab.attachSessionCallbacks(
            callbacks = state,
            onOpenNewSessionRequest = { uri ->
                runCatching {
                    GeckoResult.fromValue(currentOnOpenNewSessionRequest(uri))
                }.getOrElse { error ->
                    GeckoResult.fromException(error)
                }
            },
            onCloseRequest = { currentOnCloseTab?.invoke() },
        )
        val promptDelegate = dialogState.createPromptDelegate()
        val mediaSessionDelegate = GeckoMediaSessionDelegate(mediaWebExtension)

        session.promptDelegate = promptDelegate
        // MediaSession の初回イベントを取りこぼさないよう、ページ読み込み前に delegate を設定する。
        session.mediaSessionDelegate = mediaSessionDelegate

        browserSessionLifecycleController.restoreSession(browserTab)

        onDispose {
            browserTab.detachSessionCallbacks()
            session.promptDelegate = null
            if (session.mediaSessionDelegate === mediaSessionDelegate
                && !mediaWebExtension.shouldKeepSessionAttached(session)
            ) {
                session.mediaSessionDelegate = null
            }
        }
    }

    // テキスト選択メニューにカスタムアクション（検索/開く）を追加
    DisposableEffect(session, enableTabUi, searchTemplate) {
        val activity = context as Activity
        val delegate = object : BasicSelectionActionDelegate(activity) {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val result = super.onCreateActionMode(mode, menu)

                // コピー等の標準項目・他アプリの後にカスタム項目を末尾追加
                val text = mSelection?.text?.trim() ?: ""
                if (text.isNotBlank()) {
                    val isUrl = text.startsWith("http://") || text.startsWith("https://") ||
                        (!text.contains(" ") && text.contains("."))
                    if (isUrl) {
                        val title = if (enableTabUi) "新しいタブで開く" else "開く"
                        menu.add(Menu.NONE, MENU_ID_OPEN, Menu.NONE, title)
                    } else {
                        menu.add(Menu.NONE, MENU_ID_SEARCH, Menu.NONE, "検索")
                    }
                }

                return result
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val text = mSelection?.text?.trim()
                    ?: return super.onActionItemClicked(mode, item)
                when (item.itemId) {
                    MENU_ID_SEARCH -> {
                        val url = searchTemplate.replace(
                            "%s",
                            URLEncoder.encode(text, "UTF-8"),
                        )
                        if (enableTabUi) {
                            currentOnOpenNewTabRequest(url)
                        } else {
                            state.onUrlSubmit(url)
                        }
                        mode.finish()
                        return true
                    }
                    MENU_ID_OPEN -> {
                        val url = if (text.startsWith("http://") || text.startsWith("https://")) {
                            text
                        } else {
                            "https://$text"
                        }
                        if (enableTabUi) {
                            currentOnOpenNewTabRequest(url)
                        } else {
                            state.onUrlSubmit(url)
                        }
                        mode.finish()
                        return true
                    }
                }
                return super.onActionItemClicked(mode, item)
            }
        }
        session.selectionActionDelegate = delegate
        onDispose {
            session.selectionActionDelegate = null
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
            // 上部（ステータスバー）は BrowserToolBar の背景色で塗りつぶすため除外する
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .imePadding()
    ) {
        if (state.showFindInPage) {
            FindInPageBar(
                query = state.findQuery,
                matchCurrent = state.findMatchCurrent,
                matchTotal = state.findMatchTotal,
                isRegex = state.findIsRegex,
                queryError = state.findQueryError,
                onQueryChange = state::onFindQueryChange,
                onNext = state::findNext,
                onPrevious = state::findPrevious,
                onClose = state::closeFindInPage,
                onToggleRegex = state::toggleFindRegex,
            )
        } else {
            if (customTabMode || webAppMode) {
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
                    // ウェブアプリモードでは閉じるボタンを非表示にする
                    showCloseButton = customTabMode,
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
                            // クリップボードからURLを読み取り、現在のページと異なる場合に表示
                            val clipManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            val clipped = clipManager.primaryClip?.getItemAt(0)
                                ?.coerceToText(context)?.toString()?.trim()
                            clipboardUrl = if (
                                clipped != null &&
                                (clipped.startsWith("http://") || clipped.startsWith("https://")) &&
                                clipped != state.currentPageUrl
                            ) {
                                clipped
                            } else {
                                null
                            }
                            runCatching { session.setFocused(false) }
                            geckoView?.clearFocus()
                            keyboardController?.show()
                        } else {
                            state.restoreCurrentPageUrlToInput()
                            clipboardUrl = null
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
                                runCatching { state.flushAndCaptureForTabSwitch(it) }
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
                    onBack = state::onGoBack,
                    canGoBack = state.canGoBack,
                    onLongPressHistory = { showTabHistorySheet = true },
                    onRefresh = state::onRefresh,
                    onTranslatePage = { state.onTranslate(translationProvider) },
                    isSimpleView = state.isSimpleViewActive,
                    onSimpleView = state::toggleSimpleView,
                    pageZoomPercent = state.pageZoomPercent,
                    onPageZoomIn = state::pageZoomIn,
                    onPageZoomOut = state::pageZoomOut,
                    onResetPageZoom = state::resetPageZoom,
                    onHorizontalDrag = onToolbarHorizontalDrag,
                    onHorizontalDragEnd = onToolbarDragEnd,
                    onAddToHomeScreen = {
                        addToHomeUrl = state.currentPageUrl
                        addToHomeTitle = state.currentPageTitle
                        addToHomeFavicon = browserTab.faviconBitmap
                        showAddToHomeScreenDialog = true
                    },
                )
            }
            // 翻訳元・翻訳先の選択肢：検出済み言語＋英語＋日本語（重複除去）
            val detectedLang = state.detectedPageLanguage
            val languageOptions = remember(detectedLang) {
                buildList {
                    if (detectedLang != null && detectedLang != "en" && detectedLang != "ja") {
                        add(detectedLang)
                    }
                    add("en")
                    add("ja")
                }
            }
            TranslationStatusBar(
                state = state.translationState,
                onRevert = state::onRevertTranslation,
                onDismissError = state::onDismissTranslationError,
                fromLanguage = state.translationFromLanguage,
                toLanguage = state.translationToLanguage,
                fromLanguageOptions = languageOptions,
                toLanguageOptions = languageOptions,
                onFromLanguageSelected = { lang ->
                    state.onRetranslate(translationProvider, fromLanguage = lang, toLanguage = state.translationToLanguage ?: "ja")
                },
                onToLanguageSelected = { lang ->
                    state.onRetranslate(translationProvider, fromLanguage = state.translationFromLanguage, toLanguage = lang)
                },
            )
        }

        val latestOnRefresh by rememberUpdatedState { state.onRefreshFromSwipe() }
        val id = rememberSaveable { View.generateViewId() }
        Box(
            modifier = Modifier
                .weight(1f)
                .testTag(GeckoBrowserTabTestTags.GeckoContainer.testTag),
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

            BrowserTabOverlayLayer(
                state = state,
                urlBarSuggestions = urlBarSuggestions,
                onHistorySuggestionClick = { entry ->
                    state.onUrlSubmit(entry.url)
                    closeUrlInput(false)
                },
                onWebSuggestionClick = { query ->
                    state.onUrlSubmit(query)
                    closeUrlInput(false)
                },
                clipboardUrl = clipboardUrl,
                onClipboardUrlClick = { url ->
                    state.onUrlSubmit(url)
                    closeUrlInput(false)
                },
                modifier = Modifier.fillMaxSize(),
            )
            // シンプル表示オーバーレイ
            state.simpleViewArticle?.let { article ->
                SimpleViewScreen(
                    article = article,
                    onClose = state::dismissSimpleView,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        BrowserTabDialogLayer(
            state = state,
            dialogState = dialogState,
            enableTabUi = enableTabUi,
            onOpenNewTabRequest = currentOnOpenNewTabRequest,
        )
    }

    // ホームに追加ダイアログ
    if (showAddToHomeScreenDialog) {
        AddToHomeScreenDialog(
            url = addToHomeUrl,
            title = addToHomeTitle,
            favicon = addToHomeFavicon,
            onDismiss = { showAddToHomeScreenDialog = false },
        )
    }

    // タブ履歴BottomSheet
    if (showTabHistorySheet) {
        TabHistoryBottomSheet(
            items = state.tabHistoryItems.asReversed(),
            currentReversedIndex = state.tabHistoryItems.lastIndex - state.tabHistoryCurrentIndex,
            onNavigateTo = { reversedIndex ->
                showTabHistorySheet = false
                val originalIndex = state.tabHistoryItems.lastIndex - reversedIndex
                state.jumpToHistoryEntry(originalIndex)
            },
            onDismiss = { showTabHistorySheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabHistoryBottomSheet(
    items: List<BrowserTabScreenState.TabHistoryItem>,
    currentReversedIndex: Int,
    onNavigateTo: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "このタブの履歴",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        LazyColumn {
            itemsIndexed(items) { index, entry ->
                val isCurrent = index == currentReversedIndex
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isCurrent) {
                                Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onNavigateTo(index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = entry.title.ifBlank { entry.uri },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.uri,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (index < items.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

sealed interface GeckoBrowserTabTestTags {
    val id: String
    val testTag get() = "${GeckoBrowserTabTestTags::class.java.name}#$id"

    object GeckoContainer : GeckoBrowserTabTestTags { override val id = "gecko_container" }
}

private const val URL_BAR_IME_HIDE_GRACE_MS = 700L

// テキスト選択メニューのカスタム項目 ID
private const val MENU_ID_SEARCH = 0x10001
private const val MENU_ID_OPEN = 0x10002

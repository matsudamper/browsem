package net.matsudamper.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.media.GeckoMediaSessionDelegate
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.translate.TranslationPriorityLanguage
import net.matsudamper.browser.ui.common.resolveBrowserToolbarColors
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
    tabCount: Int?,
    onInstallExtensionRequest: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenNewSessionRequest: (String) -> GeckoSession,
    onOpenNewTabRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    enableTabUi: Boolean = true,
    showInstallExtensionItem: Boolean = true,
    customTabMode: Boolean = false,
    webAppMode: Boolean = false,
    onCloseCustomTab: (() -> Unit)? = null,
    onOpenInBrowser: ((String) -> Unit)? = null,
    onCloseTab: (() -> Unit)? = null,
    onToolbarHorizontalDrag: (Float) -> Unit = {},
    onToolbarDragEnd: () -> Unit = {},
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
    urlBarSuggestions: UrlBarSuggestionsUiState = UrlBarSuggestionsUiState(),
    onUrlInputChanged: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
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
    var urlBarFocusStartedAtMs by remember { mutableLongStateOf(0L) }
    var geckoView: GeckoView? by remember { mutableStateOf(null) }
    // Surface と Session の復元状態を一元管理する state machine。
    // ON_START / ON_RESUME が重複発火しても state=ACTIVE なら即 no-op にする。
    var surfaceResumeState by remember(session) { mutableStateOf(SurfaceResumeState.ACTIVE) }
    val resumeCoverColor = MaterialTheme.colorScheme.surface.toArgb()

    // ファイルピッカー（単一ファイル選択）Google Photos を含むピッカーを表示するため ACTION_GET_CONTENT を使用
    val singleFileLauncher = rememberLauncherForActivityResult(
        GetContentWithMimeTypes(),
    ) { uri ->
        if (uri != null) {
            dialogState.confirmFilePrompt(context, arrayOf(uri))
        } else {
            dialogState.dismissFilePrompt()
        }
    }

    // ファイルピッカー（複数ファイル選択）Google Photos を含むピッカーを表示するため ACTION_GET_CONTENT を使用
    val multipleFilesLauncher = rememberLauncherForActivityResult(
        GetMultipleContentsWithMimeTypes(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            dialogState.confirmFilePrompt(context, uris.toTypedArray())
        } else {
            dialogState.dismissFilePrompt()
        }
    }

    // ファイルプロンプトが来たらピッカーを起動
    val pendingFilePrompt = dialogState.pendingFilePrompt
    LaunchedEffect(pendingFilePrompt) {
        val prompt = pendingFilePrompt ?: return@LaunchedEffect
        val mimeTypes = prompt.mimeTypes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        when (prompt.type) {
            GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE ->
                multipleFilesLauncher.launch(mimeTypes)
            else ->
                singleFileLauncher.launch(mimeTypes)
        }
    }

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

    // Surface 復元処理本体。ACTIVE なら即 return。
    //
    // Column に .imePadding() が掛かっているため IME 表示中に pause すると GeckoView の
    // 親が縮む。resume 時は SurfaceView が一旦 stale 寸法で再作成され、layout settle 後に
    // 正しい寸法へ resize される。ここで Gecko compositor が attach 済みだと
    // SyncResumeResizeCompositor の IPC 経路でハング → GPU プロセス kill 発生。
    //
    // そこで ON_PAUSE 側で releaseSession 済の前提で、OneShotPreDrawListener で layout が
    // 最新寸法で計測された後に setSession で fresh attach する。fresh attach は "resume"
    // ではなく "initial" 扱いのため IPC ハング経路を通らない。
    fun restoreSurfaceIfNeeded(gecko: GeckoView) {
        if (surfaceResumeState == SurfaceResumeState.ACTIVE) return
        // stale フレームが一瞬表示されるのを防ぐため pre-draw 待ちより前に cover する。
        gecko.coverUntilFirstPaint(resumeCoverColor)
        OneShotPreDrawListener.add(gecko) {
            if (surfaceResumeState == SurfaceResumeState.ACTIVE) return@add
            // ON_RESUME→ON_PAUSE の短時間遷移で遅延 callback がフォアグラウンド外で
            // 実行されると、paused activity で session を再活性化した上に stale サイズで
            // setActive(true) を呼んでしまい本来防ぎたいハング経路に再突入する。
            // 次回の ON_START / ON_RESUME で改めて登録されるので、ここでは state 遷移させずに抜ける。
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@add
            }
            gecko.setSession(session)
            session.setActive(true)
            surfaceResumeState = SurfaceResumeState.ACTIVE
        }
    }

    DisposableEffect(lifecycleOwner, session, resumeCoverColor) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // ON_STOP まで待つと surface 破棄→再作成時に GeckoView 内部の
                    // SurfaceHolder.Callback が Gecko compositor を自動 resume-resize させ、
                    // IME 由来の stale サイズで frame 産出 → BLAST reject → GPU プロセス kill
                    // というハングが発生する。ON_PAUSE 時点で releaseSession して session を
                    // GeckoView から detach しておけば、surface 再作成時の自動レンダリングを
                    // 抑止できる。
                    //
                    // capture preview は release 前に start する。capturePixels() は非同期
                    // GeckoResult を返すため release 直後に走るキャプチャ完了率は低下するが、
                    // ハング回避を優先する。
                    if (surfaceResumeState == SurfaceResumeState.ACTIVE &&
                        !mediaWebExtension.shouldKeepSessionAttached(session)
                    ) {
                        geckoView?.also { gv ->
                            session.setActive(false)
                            // best-effort capture（非同期 GeckoResult、release 後に失敗する可能性あり）。
                            state.captureTabPreview(gv)
                            // surface 再作成時の自動 compositor resume を防ぐため即 detach。
                            gv.releaseSession()
                            surfaceResumeState = SurfaceResumeState.RELEASED
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    session.flushSessionState()
                    // non-media の場合は ON_PAUSE で release 済み。
                    // media の場合は session 維持のため capture のみ実行（従来どおり）。
                    // TODO: media 再生継続中の session は release しないため、surface 再作成時の
                    //       SyncResumeResizeCompositor ハング経路を踏むリスクが残る。実機で
                    //       再現を確認したら、audio を殺さない形で compositor 再構築する手段
                    //       （releaseSession しても MediaSession 経由で音は継続する可能性が高い）
                    //       を検討する。
                    geckoView?.also { gv ->
                        if (mediaWebExtension.shouldKeepSessionAttached(session)) {
                            state.captureTabPreview(gv)
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    geckoView?.also(::restoreSurfaceIfNeeded)
                }
                Lifecycle.Event.ON_RESUME -> {
                    geckoView?.also(::restoreSurfaceIfNeeded)
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
    BackHandler(enabled = state.canGoBack && !state.isUrlInputFocused) {
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
                    toolbarColor = state.toolbarColor,
                    onRefresh = state::onRefresh,
                    onSuperRefresh = state::onSuperRefresh,
                    onHome = state::onHome,
                    onForward = state::onGoForward,
                    canGoForward = state.canGoForward,
                    onBack = state::onGoBack,
                    canGoBack = state.canGoBack,
                    onLongPressHistory = { showTabHistorySheet = true },
                    isPcMode = state.isPcMode,
                    onPcModeToggle = state::togglePcMode,
                    showInstallExtensionItem = showInstallExtensionItem && state.showInstallExtensionItem,
                    onInstallExtension = { onInstallExtensionRequest(state.currentPageUrl) },
                    onTranslatePage = { state.onTranslate(translationProvider) },
                    onShare = state::sharePage,
                    onFindInPage = state::openFindInPage,
                    onAddToHomeScreen = state::requestAddToHomeScreen,
                    // ウェブアプリモードでは「ホームに追加」を非表示
                    showAddToHomeScreen = !webAppMode,
                    onOpenInBrowser = onOpenInBrowser?.let { callback ->
                        { callback(state.currentPageUrl) }
                    },
                    pageZoomPercent = state.pageZoomPercent,
                    onPageZoomIn = state::pageZoomIn,
                    onPageZoomOut = state::pageZoomOut,
                    onResetPageZoom = state::resetPageZoom,
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
                    onSuperRefresh = state::onSuperRefresh,
                    onTranslatePage = { state.onTranslate(translationProvider) },
                    pageZoomPercent = state.pageZoomPercent,
                    onPageZoomIn = state::pageZoomIn,
                    onPageZoomOut = state::pageZoomOut,
                    onResetPageZoom = state::resetPageZoom,
                    onHorizontalDrag = onToolbarHorizontalDrag,
                    onHorizontalDragEnd = {
                        // タブ切替スワイプになる可能性があるため、現在のタブのプレビューを事前にキャプチャする
                        geckoView?.also { gv ->
                            runCatching { state.flushAndCaptureForTabSwitch(gv) }
                        }
                        onToolbarDragEnd()
                    },
                    onAddToHomeScreen = state::requestAddToHomeScreen,
                )
            }
            // 翻訳元・翻訳先の選択肢：検出済み言語＋英語＋日本語（重複除去）
            val detectedLang = state.detectedPageLanguage
            val languageOptions = remember(detectedLang) {
                buildList {
                    if (detectedLang != null && detectedLang != TranslationPriorityLanguage.FROM && detectedLang != TranslationPriorityLanguage.TO) {
                        add(detectedLang)
                    }
                    add(TranslationPriorityLanguage.FROM)
                    add(TranslationPriorityLanguage.TO)
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
                    state.onRetranslate(translationProvider, fromLanguage = lang, toLanguage = state.translationToLanguage ?: TranslationPriorityLanguage.TO)
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
        }
        BrowserTabDialogLayer(
            state = state,
            dialogState = dialogState,
            enableTabUi = enableTabUi,
            onOpenNewTabRequest = currentOnOpenNewTabRequest,
        )
    }

    // ホームに追加ダイアログ
    state.addToHomeScreenState?.let { addToHomeScreenState ->
        AddToHomeScreenDialog(
            url = addToHomeScreenState.url,
            title = addToHomeScreenState.title,
            favicon = addToHomeScreenState.favicon,
            isIconLoading = addToHomeScreenState.isIconLoading,
            onDismiss = state::dismissAddToHomeScreen,
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

/**
 * Surface/Session 復元の進行状態。
 *
 * - ACTIVE: 前面表示中。復元処理は全て no-op。
 * - RELEASED: ON_PAUSE で releaseSession() 済。ON_START で setSession が必要。
 */
private enum class SurfaceResumeState {
    ACTIVE,
    RELEASED,
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

/**
 * ACTION_GET_CONTENT を使った単一ファイル選択コントラクト。
 * OpenDocument と異なり Google Photos などのフォトアプリもピッカーに表示される。
 */
private class GetContentWithMimeTypes : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            applyMimeTypes(this, input)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

/**
 * ACTION_GET_CONTENT を使った複数ファイル選択コントラクト。
 * OpenMultipleDocuments と異なり Google Photos などのフォトアプリもピッカーに表示される。
 */
private class GetMultipleContentsWithMimeTypes : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            applyMimeTypes(this, input)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clipData = intent.clipData
        return if (clipData != null) {
            // 一部のピッカーは clipData に加え intent.data にも先頭URIを入れるため、両方をマージして重複を除去する
            val uris = mutableListOf<Uri>()
            intent.data?.let { uris.add(it) }
            for (i in 0 until clipData.itemCount) {
                uris.add(clipData.getItemAt(i).uri)
            }
            uris.distinct()
        } else {
            listOfNotNull(intent.data)
        }
    }
}

/** MIME タイプを Intent に適用する共通関数 */
private fun applyMimeTypes(intent: Intent, mimeTypes: Array<String>) {
    when {
        mimeTypes.isEmpty() -> intent.type = "*/*"
        mimeTypes.size == 1 -> intent.type = mimeTypes[0]
        else -> {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
    }
}

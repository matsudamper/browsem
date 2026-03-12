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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.media.GeckoMediaSessionDelegate
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.screen.browser.UrlBarSuggestionsUiState
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
    urlBarSuggestions: UrlBarSuggestionsUiState = UrlBarSuggestionsUiState(),
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
        val delegates = createGeckoSessionDelegateBundle(
            callbacks = state,
            onDesktopNotificationPermissionRequest = { uri ->
                currentOnDesktopNotificationPermissionRequest(uri)
            },
            onOpenNewSessionRequest = { uri ->
                currentOnOpenNewSessionRequest(uri)
            },
            onCloseRequest = { currentOnCloseTab?.invoke() },
        )
        val promptDelegate = dialogState.createPromptDelegate()

        session.permissionDelegate = delegates.permissionDelegate
        session.navigationDelegate = delegates.navigationDelegate
        session.contentDelegate = delegates.contentDelegate
        session.progressDelegate = delegates.progressDelegate
        session.translationsSessionDelegate = delegates.translationsDelegate
        session.scrollDelegate = delegates.scrollDelegate
        session.promptDelegate = promptDelegate

        browserSessionController.restoreSession(browserTab)

        onDispose {
            session.permissionDelegate = null
            session.navigationDelegate = null
            session.contentDelegate = null
            session.progressDelegate = null
            session.translationsSessionDelegate = null
            session.scrollDelegate = null
            session.promptDelegate = null
        }
    }

    // メディアセッションデリゲートは不安定なラムダキーの影響を受けないよう独立して管理
    DisposableEffect(session, mediaWebExtension) {
        val mediaSessionDelegate = GeckoMediaSessionDelegate(mediaWebExtension)
        session.mediaSessionDelegate = mediaSessionDelegate
        onDispose {
            if (session.mediaSessionDelegate === mediaSessionDelegate) {
                session.mediaSessionDelegate = null
            }
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
                modifier = Modifier.fillMaxSize(),
            )
        }
        BrowserTabDialogLayer(
            state = state,
            dialogState = dialogState,
            enableTabUi = enableTabUi,
            onOpenNewSessionRequest = currentOnOpenNewSessionRequest,
        )
    }
}
private const val URL_BAR_IME_HIDE_GRACE_MS = 700L

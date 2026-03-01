package net.matsudamper.browser

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GeckoBrowserTab(
    tabId: Long,
    session: GeckoSession,
    initialUrl: String,
    homepageUrl: String,
    searchTemplate: String,
    modifier: Modifier = Modifier,
    tabCount: Int,
    onInstallExtensionRequest: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTabs: () -> Unit,
    onCurrentPageUrlChange: (String) -> Unit,
    onSessionStateChange: (String) -> Unit,
    onTabPreviewCaptured: (Bitmap) -> Unit,
    onTabTitleChange: (String) -> Unit,
) {
    var urlInput by rememberSaveable(tabId) { mutableStateOf(initialUrl) }
    var currentPageUrl by rememberSaveable(tabId) { mutableStateOf(initialUrl) }
    var canGoBack by remember(tabId) { mutableStateOf(false) }
    var isUrlInputFocused by remember(tabId) { mutableStateOf(false) }
    var geckoViewRef by remember(tabId) { mutableStateOf<GeckoView?>(null) }
    var isPcMode by rememberSaveable(tabId) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isImeVisible = WindowInsets.isImeVisible

    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatchCurrent by remember { mutableIntStateOf(0) }
    var findMatchTotal by remember { mutableIntStateOf(0) }

    fun closeFindInPage() {
        showFindInPage = false
        session.finder.clear()
        findQuery = ""
        findMatchCurrent = 0
        findMatchTotal = 0
    }

    fun captureCurrentTabPreview() {
        val view = geckoViewRef ?: return
        view.capturePixels().accept(
            { bitmap ->
                val previewBitmap = bitmap ?: return@accept
                view.post {
                    onTabPreviewCaptured(previewBitmap)
                }
            },
            {},
        )
    }

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.flushSessionState()
                captureCurrentTabPreview()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(session) {
        val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack = value
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val newUrl = url.orEmpty()
                currentPageUrl = newUrl
                onCurrentPageUrlChange(newUrl)
                if (!isUrlInputFocused) {
                    urlInput = newUrl
                }
            }
        }
        val contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let { onTabTitleChange(it) }
            }
        }
        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState
            ) {
                onSessionStateChange(sessionState.toString().orEmpty())
            }
        }
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.progressDelegate = progressDelegate

        onDispose {
            if (session.navigationDelegate === navigationDelegate) {
                session.navigationDelegate = null
            }
            if (session.contentDelegate === contentDelegate) {
                session.contentDelegate = null
            }
            if (session.progressDelegate === progressDelegate) {
                session.progressDelegate = null
            }
        }
    }

    BackHandler(enabled = showFindInPage) {
        closeFindInPage()
    }

    BackHandler(enabled = canGoBack) {
        session.goBack()
    }

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            isUrlInputFocused = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        if (showFindInPage) {
            FindInPageBar(
                query = findQuery,
                matchCurrent = findMatchCurrent,
                matchTotal = findMatchTotal,
                onQueryChange = { newQuery ->
                    findQuery = newQuery
                    if (newQuery.isEmpty()) {
                        session.finder.clear()
                        findMatchCurrent = 0
                        findMatchTotal = 0
                    } else {
                        session.finder.find(newQuery, 0).then<Void?> { result ->
                            findMatchCurrent = result?.current ?: 0
                            findMatchTotal = result?.total ?: 0
                            null
                        }
                    }
                },
                onNext = {
                    if (findQuery.isNotEmpty()) {
                        session.finder.find(findQuery, 0).then<Void?> { result ->
                            findMatchCurrent = result?.current ?: 0
                            findMatchTotal = result?.total ?: 0
                            null
                        }
                    }
                },
                onPrevious = {
                    if (findQuery.isNotEmpty()) {
                        session.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS).then<Void?> { result ->
                            findMatchCurrent = result?.current ?: 0
                            findMatchTotal = result?.total ?: 0
                            null
                        }
                    }
                },
                onClose = {
                    closeFindInPage()
                },
            )
        } else {
            BrowserToolBar(
                value = urlInput,
                onValueChange = { urlInput = it },
                onSubmit = { rawInput ->
                    val resolved = buildUrlFromInput(rawInput, homepageUrl, searchTemplate)
                    urlInput = resolved
                    currentPageUrl = resolved
                    onCurrentPageUrlChange(resolved)
                    session.loadUri(resolved)
                    keyboardController?.hide()
                },
                onFocusChanged = { hasFocus -> isUrlInputFocused = hasFocus },
                showInstallExtensionItem = resolveAmoInstallUriFromPage(currentPageUrl) != null,
                onInstallExtension = {
                    onInstallExtensionRequest(currentPageUrl)
                },
                onOpenSettings = onOpenSettings,
                tabCount = tabCount,
                onOpenTabs = {
                    session.flushSessionState()
                    captureCurrentTabPreview()
                    onOpenTabs()
                },
                onFindInPage = {
                    isUrlInputFocused = false
                    showFindInPage = true
                },
                isPcMode = isPcMode,
                onPcModeToggle = {
                    val newMode = !isPcMode
                    isPcMode = newMode
                    session.settings.userAgentMode = if (newMode) {
                        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    } else {
                        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    }
                    session.reload()
                },
            )
        }

        AndroidView(
            factory = { context ->
                GeckoView(context).also { geckoView ->
                    geckoView.setSession(session)
                    geckoViewRef = geckoView
                }
            },
            update = { geckoView ->
                geckoView.setSession(session)
                geckoViewRef = geckoView
                val shouldFocusWebContent = isUrlInputFocused.not()
                geckoView.isFocusable = shouldFocusWebContent
                geckoView.isFocusableInTouchMode = shouldFocusWebContent
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun FindInPageBar(
    query: String,
    matchCurrent: Int,
    matchTotal: Int,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "ページ内を検索...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Text(
                    text = "$matchCurrent/$matchTotal",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = onPrevious,
                enabled = query.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "前へ",
                )
            }
            IconButton(
                onClick = onNext,
                enabled = query.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "次へ",
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "閉じる",
                )
            }
        }
    }
}

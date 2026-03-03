package net.matsudamper.browser

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import net.matsudamper.browser.data.TranslationProvider
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.TranslationsController
import java.net.URL
import java.util.concurrent.Executors
import androidx.core.graphics.toColorInt

private enum class TranslationState { Idle, Loading, Translated, Error }

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GeckoBrowserTab(
    tabId: Long,
    session: GeckoSession,
    initialUrl: String,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    modifier: Modifier = Modifier,
    tabCount: Int,
    onInstallExtensionRequest: (String) -> Unit,
    onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    onOpenSettings: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenNewSessionRequest: (String) -> GeckoSession,
    onCurrentPageUrlChange: (String) -> Unit,
    onSessionStateChange: (String) -> Unit,
    onTabPreviewCaptured: (Bitmap) -> Unit,
    onTabTitleChange: (String) -> Unit,
) {
    var urlInput by rememberSaveable(tabId) { mutableStateOf(initialUrl) }
    var currentPageUrl by rememberSaveable(tabId) { mutableStateOf(initialUrl) }
    var currentPageTitle by rememberSaveable(tabId) { mutableStateOf("") }
    var canGoBack by remember(tabId) { mutableStateOf(false) }
    var canGoForward by remember(tabId) { mutableStateOf(false) }
    var isUrlInputFocused by remember(tabId) { mutableStateOf(false) }
    var geckoViewRef by remember(tabId) { mutableStateOf<GeckoView?>(null) }
    var isPcMode by rememberSaveable(tabId) { mutableStateOf(false) }
    var toolbarColor by remember(tabId) { mutableStateOf<Color?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isImeVisible = WindowInsets.isImeVisible
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scope = coroutineScope
    val geckoDownloadManager = remember(context) {
        GeckoDownloadManager(
            context = context,
            runtime = GeckoRuntime.getDefault(context),
        )
    }

    var translationState by remember(tabId) { mutableStateOf(TranslationState.Idle) }
    var originalPageUrlForRevert by remember(tabId) { mutableStateOf<String?>(null) }
    var detectedPageLanguage by remember(tabId) { mutableStateOf<String?>(null) }

    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatchCurrent by remember { mutableIntStateOf(0) }
    var findMatchTotal by remember { mutableIntStateOf(0) }
    var imageContextMenuUrl by remember(tabId) { mutableStateOf<String?>(null) }

    // Pull to refresh state
    val isRefreshingState = remember(tabId) { mutableStateOf(false) }
    var isRefreshing by isRefreshingState
    // Tracks GeckoView scroll position to enable/disable pull-to-refresh at page top
    val geckoScrollYRef = remember(tabId) { intArrayOf(0) }
    // Keeps latest session reference accessible from the SwipeRefreshLayout factory closure
    val currentSessionHolder = remember(tabId) { arrayOf<GeckoSession?>(null) }
    currentSessionHolder[0] = session

    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()

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
        val permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                    )
                }
                return onDesktopNotificationPermissionRequest()
            }
        }

        val navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack = value
            }

            override fun onCanGoForward(session: GeckoSession, value: Boolean) {
                canGoForward = value
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                return GeckoResult.fromValue(onOpenNewSessionRequest(uri))
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val newUrl = url.orEmpty()
                if (newUrl == "about:blank" && currentPageUrl != "about:blank") {
                    // セッション再アタッチ時の一時的な about:blank をスキップ
                    return
                }
                currentPageUrl = newUrl
                onCurrentPageUrlChange(newUrl)
                if (!isUrlInputFocused) {
                    urlInput = newUrl
                }
                toolbarColor = null
                // ユーザーが別ページへ手動遷移した場合、翻訳状態をリセット
                val revertUrl = originalPageUrlForRevert
                if (translationState != TranslationState.Idle &&
                    !newUrl.startsWith("data:") &&
                    newUrl != revertUrl
                ) {
                    translationState = TranslationState.Idle
                    originalPageUrlForRevert = null
                }
                if (!newUrl.startsWith("data:")) {
                    detectedPageLanguage = null
                }
            }
        }
        val contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val newTitle = title.orEmpty()
                currentPageTitle = newTitle
                onTabTitleChange(newTitle)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {

                when (element.type) {
                    GeckoSession.ContentDelegate.ContextElement.TYPE_NONE,
                    GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO,
                    GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> {
                    }

                    GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> {
                        imageContextMenuUrl = element.srcUri
                    }
                }
            }
        }
        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState
            ) {
                onSessionStateChange(sessionState.toString().orEmpty())
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isRefreshingState.value = false
                val pageUrl = currentPageUrl
                updateThemeColorFromPage(pageUrl) { resolvedUrl, color ->
                    if (resolvedUrl == currentPageUrl) {
                        toolbarColor = color
                    }
                }
            }
        }
        val scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                geckoScrollYRef[0] = scrollY
            }
        }
        val translationsDelegate = object : TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationsController.SessionTranslation.TranslationState?
            ) {
                val lang = translationState?.detectedLanguages?.docLangTag ?: return
                detectedPageLanguage = lang
            }
        }

        session.permissionDelegate = permissionDelegate
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.progressDelegate = progressDelegate
        session.scrollDelegate = scrollDelegate
        session.translationsSessionDelegate = translationsDelegate

        onDispose {
            if (session.permissionDelegate === permissionDelegate) {
                session.permissionDelegate = null
            }
            if (session.navigationDelegate === navigationDelegate) {
                session.navigationDelegate = null
            }
            if (session.contentDelegate === contentDelegate) {
                session.contentDelegate = null
            }
            if (session.progressDelegate === progressDelegate) {
                session.progressDelegate = null
            }
            if (session.scrollDelegate === scrollDelegate) {
                session.scrollDelegate = null
            }
            if (session.translationsSessionDelegate === translationsDelegate) {
                session.translationsSessionDelegate = null
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
                        session.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS)
                            .then<Void?> { result ->
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
                onShare = {
                    val shareText = "$currentPageTitle\n$currentPageUrl"
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                },
                tabCount = tabCount,
                onOpenTabs = {
                    session.flushSessionState()
                    captureCurrentTabPreview()
                    onOpenTabs()
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
                onFindInPage = {
                    showFindInPage = true
                },
                toolbarColor = toolbarColor,
                onHome = {
                    urlInput = homepageUrl
                    currentPageUrl = homepageUrl
                    session.loadUri(homepageUrl)
                },
                onForward = { session.goForward() },
                canGoForward = canGoForward,
                onRefresh = { session.reload() },
                onTranslatePage = {
                    if (translationState != TranslationState.Loading) {
                        scope.launch {
                            originalPageUrlForRevert = currentPageUrl
                            translationState = TranslationState.Loading
                            val result = runCatching {
                                PageTranslator(session, currentPageUrl).translatePageToJapanese(translationProvider, detectedPageLanguage)
                            }
                            translationState = if (result.isSuccess) {
                                TranslationState.Translated
                            } else {
                                TranslationState.Error
                            }
                        }
                    }
                },
            )
            TranslationStatusBar(
                state = translationState,
                onRevert = {
                    val savedUrl = originalPageUrlForRevert
                    translationState = TranslationState.Idle
                    originalPageUrlForRevert = null
                    if (savedUrl != null) {
                        session.loadUri(savedUrl)
                    }
                },
                onDismissError = {
                    translationState = TranslationState.Idle
                    originalPageUrlForRevert = null
                },
            )
        }

        AndroidView(
            factory = { ctx ->
                val geckoView = GeckoView(ctx).also { gv ->
                    gv.setAutofillEnabled(true)
                    gv.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                    gv.setSession(session)
                    geckoViewRef = gv
                }
                SwipeRefreshLayout(ctx).also { srl ->
                    srl.addView(
                        geckoView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    srl.setColorSchemeColors(primaryColor)
                    // Allow pull-to-refresh only when the page is scrolled to the top
                    srl.setOnChildScrollUpCallback { _, _ -> geckoScrollYRef[0] > 0 }
                    srl.setOnRefreshListener {
                        isRefreshingState.value = true
                        currentSessionHolder[0]?.reload()
                    }
                }
            },
            update = { srl ->
                srl.isRefreshing = isRefreshing
                val geckoView = srl.getChildAt(0) as GeckoView
                geckoView.setAutofillEnabled(true)
                geckoView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
                geckoView.setSession(session)
                geckoViewRef = geckoView
                geckoView.isFocusable = true
                geckoView.isFocusableInTouchMode = true
                if (!isUrlInputFocused && !geckoView.isFocused) {
                    geckoView.requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        imageContextMenuUrl?.let { imageUrl ->
            AlertDialog(
                onDismissRequest = { imageContextMenuUrl = null },
                title = {
                    Text(text = "画像")
                },
                text = {
                    Text(text = "この画像をダウンロードしますか？")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            imageContextMenuUrl = null
                            coroutineScope.launch {
                                val result = runCatching {
                                    geckoDownloadManager.downloadImageWithSession(
                                        imageUrl = imageUrl,
                                        referrerUrl = currentPageUrl,
                                    )
                                }.onFailure { it.printStackTrace() }
                                val message = if (result.isSuccess) {
                                    "画像をダウンロードしました"
                                } else {
                                    "画像のダウンロードに失敗しました"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text(text = "ダウンロード")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { imageContextMenuUrl = null }) {
                        Text(text = "キャンセル")
                    }
                },
            )
        }
    }
}


private val themeColorHandler = Handler(Looper.getMainLooper())
private val themeColorExecutor = Executors.newSingleThreadExecutor()

private fun updateThemeColorFromPage(
    pageUrl: String,
    onColorResolved: (String, Color?) -> Unit,
) {
    if (pageUrl.isBlank()) {
        onColorResolved(pageUrl, null)
        return
    }

    themeColorExecutor.execute {
        val colorText = runCatching {
            URL(pageUrl).openConnection().getInputStream().bufferedReader().use { reader ->
                reader.readText()
            }
        }.getOrNull()?.let(::findThemeColorMetaContent)

        val parsedColor = colorText?.let(::parseManifestColor)
        themeColorHandler.post {
            onColorResolved(pageUrl, parsedColor)
        }
    }
}

private fun findThemeColorMetaContent(html: String): String? {
    val metaTagRegex = Regex("""<meta\b[^>]*>""", setOf(RegexOption.IGNORE_CASE))
    val nameRegex = Regex("""\bname\s*=\s*(["'])theme-color\1""", setOf(RegexOption.IGNORE_CASE))
    val contentRegex = Regex("""\bcontent\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE))

    return metaTagRegex.findAll(html).firstNotNullOfOrNull { matchResult ->
        val tag = matchResult.value
        if (nameRegex.containsMatchIn(tag).not()) {
            return@firstNotNullOfOrNull null
        }
        contentRegex.find(tag)?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun parseManifestColor(colorValue: String): Color? {
    return try {
        Color(colorValue.toColorInt())
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Composable
private fun TranslationStatusBar(
    state: TranslationState,
    onRevert: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == TranslationState.Idle) return

    val backgroundColor = when (state) {
        TranslationState.Loading,
        TranslationState.Translated -> MaterialTheme.colorScheme.secondaryContainer
        TranslationState.Error -> MaterialTheme.colorScheme.errorContainer
        TranslationState.Idle -> return
    }

    Surface(
        color = backgroundColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            if (state == TranslationState.Loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = when (state) {
                        TranslationState.Loading -> "翻訳中..."
                        TranslationState.Translated -> "翻訳済み"
                        TranslationState.Error -> "翻訳に失敗しました"
                        TranslationState.Idle -> ""
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        TranslationState.Error -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                when (state) {
                    TranslationState.Translated -> {
                        TextButton(onClick = onRevert) {
                            Text(text = "元に戻す")
                        }
                    }
                    TranslationState.Error -> {
                        IconButton(onClick = onDismissError) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "閉じる",
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
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

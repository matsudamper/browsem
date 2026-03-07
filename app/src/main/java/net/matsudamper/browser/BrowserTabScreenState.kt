package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.TranslationProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayOutputStream


@Composable
internal fun rememberBrowserTabScreenState(
    browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
): BrowserTabScreenState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geckoDownloadManager = remember(context) {
        GeckoDownloadManager(
            context = context,
            runtime = GeckoRuntime.getDefault(context),
        )
    }
    val state = remember(browserTab) {
        BrowserTabScreenState(
            browserTab = browserTab,
            homepageUrl = homepageUrl,
            searchTemplate = searchTemplate,
            coroutineScope = coroutineScope,
            geckoDownloadManager = geckoDownloadManager,
            context = context,
            onHistoryRecord = onHistoryRecord,
            onHistoryTitleUpdate = onHistoryTitleUpdate,
        )
    }
    state.homepageUrl = homepageUrl
    state.searchTemplate = searchTemplate
    state.onHistoryRecord = onHistoryRecord
    state.onHistoryTitleUpdate = onHistoryTitleUpdate
    return state
}

@Stable
internal class BrowserTabScreenState(
    val browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    private val coroutineScope: CoroutineScope,
    private val geckoDownloadManager: GeckoDownloadManager,
    private val context: Context,
    var onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    var onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
) {
    // 現在のページの履歴エントリID（タイトル更新に使用）
    private var currentHistoryEntryId: Long? = null
    var homepageUrl by mutableStateOf(homepageUrl)
    var searchTemplate by mutableStateOf(searchTemplate)
    val session: GeckoSession get() = browserTab.session

    // --- URL / Navigation state ---
    var urlInput by mutableStateOf(browserTab.currentUrl)
    var currentPageUrl by mutableStateOf(browserTab.currentUrl)
    var currentPageTitle by mutableStateOf(browserTab.title)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var isUrlInputFocused by mutableStateOf(false)

    // --- Display state ---
    var isPcMode by mutableStateOf(false)
    var toolbarColor by mutableStateOf<Color?>(null)
    private var lastPageStartUrlKey: String = normalizedToolbarUrlKey(browserTab.currentUrl)

    // --- Translation state ---
    var translationState by mutableStateOf(TranslationState.Idle)
    var originalPageUrlForRevert by mutableStateOf<String?>(null)
    var detectedPageLanguage by mutableStateOf<String?>(null)

    // --- Find-in-page state ---
    var showFindInPage by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findMatchCurrent by mutableIntStateOf(0)
    var findMatchTotal by mutableIntStateOf(0)

    // --- Context menu / dialog state ---
    var imageContextMenuUrl by mutableStateOf<String?>(null)
    var linkContextMenuUrl by mutableStateOf<String?>(null)
    var pendingAlertPrompt by mutableStateOf<GeckoSession.PromptDelegate.AlertPrompt?>(null)
    var pendingAlertResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)
    var pendingButtonPrompt by mutableStateOf<GeckoSession.PromptDelegate.ButtonPrompt?>(null)
    var pendingButtonResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)
    var pendingTextPrompt by mutableStateOf<GeckoSession.PromptDelegate.TextPrompt?>(null)
    var pendingTextResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)
    var pendingChoicePrompt by mutableStateOf<GeckoSession.PromptDelegate.ChoicePrompt?>(null)
    var pendingChoiceResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)
    var pendingColorPrompt by mutableStateOf<GeckoSession.PromptDelegate.ColorPrompt?>(null)
    var pendingColorResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)
    var pendingDateTimePrompt by mutableStateOf<GeckoSession.PromptDelegate.DateTimePrompt?>(null)
    var pendingDateTimeResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- ファイルダウンロード確認ダイアログ用state ---
    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)

    var renderReady by mutableStateOf(false)

    // --- Scroll / Refresh state ---
    var isRefreshing by mutableStateOf(false)
    var scrollY by mutableIntStateOf(0)

    val showInstallExtensionItem: Boolean
        get() = resolveAmoInstallUriFromPage(currentPageUrl) != null

    // ================================================================
    // Actions
    // ================================================================

    fun onUrlSubmit(rawInput: String) {
        val resolved = buildUrlFromInput(rawInput, homepageUrl, searchTemplate)
        urlInput = resolved
        maybeResetToolbarColor(currentPageUrl, resolved)
        currentPageUrl = resolved
        session.loadUri(resolved)
    }

    fun onHome() {
        urlInput = homepageUrl
        maybeResetToolbarColor(currentPageUrl, homepageUrl)
        currentPageUrl = homepageUrl
        session.loadUri(homepageUrl)
    }

    fun onRefresh() {
        session.reload()
    }

    fun onRefreshFromSwipe() {
        session.reload()
        isRefreshing = false
    }

    fun onGoForward() {
        session.goForward()
    }

    fun onGoBack() {
        session.goBack()
    }

    fun togglePcMode() {
        val newMode = !isPcMode
        isPcMode = newMode
        session.settings.userAgentMode = if (newMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.reload()
    }

    fun openFindInPage() {
        showFindInPage = true
    }

    fun closeFindInPage() {
        showFindInPage = false
        session.finder.clear()
        findQuery = ""
        findMatchCurrent = 0
        findMatchTotal = 0
    }

    fun onFindQueryChange(newQuery: String) {
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
    }

    fun findNext() {
        if (findQuery.isNotEmpty()) {
            session.finder.find(findQuery, 0).then<Void?> { result ->
                findMatchCurrent = result?.current ?: 0
                findMatchTotal = result?.total ?: 0
                null
            }
        }
    }

    fun findPrevious() {
        if (findQuery.isNotEmpty()) {
            session.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS)
                .then<Void?> { result ->
                    findMatchCurrent = result?.current ?: 0
                    findMatchTotal = result?.total ?: 0
                    null
                }
        }
    }

    fun onTranslate(translationProvider: TranslationProvider) {
        if (translationState == TranslationState.Loading) return
        coroutineScope.launch {
            originalPageUrlForRevert = currentPageUrl
            translationState = TranslationState.Loading
            val result = runCatching {
                PageTranslator(session, currentPageUrl).translatePageToJapanese(
                    translationProvider,
                    detectedPageLanguage,
                )
            }
            translationState = if (result.isSuccess) {
                TranslationState.Translated
            } else {
                TranslationState.Error
            }
        }
    }

    fun onRevertTranslation() {
        val savedUrl = originalPageUrlForRevert
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        if (savedUrl != null) {
            session.loadUri(savedUrl)
        }
    }

    fun onDismissTranslationError() {
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
    }

    fun sharePage() {
        val shareText = "$currentPageTitle\n$currentPageUrl"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun downloadImage(imageUrl: String) {
        imageContextMenuUrl = null
        // WorkManagerにエンキューして通知で進捗表示
        geckoDownloadManager.enqueueDownload(
            url = imageUrl,
            referrerUrl = currentPageUrl,
        )
    }

    // GeckoViewがレンダリングできないレスポンス（ダウンロードリンク等）を受け取った際に呼ばれる
    // ユーザーに確認ダイアログを表示するため、pendingDownloadResponseに保持する
    fun downloadFileFromResponse(response: WebResponse) {
        pendingDownloadResponse = response
    }

    fun confirmPendingDownload() {
        val response = pendingDownloadResponse ?: return
        pendingDownloadResponse = null
        geckoDownloadManager.enqueueDownloadFromResponse(
            response = response,
            referrerUrl = currentPageUrl,
        )
    }

    fun dismissPendingDownload() {
        pendingDownloadResponse?.body?.close()
        pendingDownloadResponse = null
    }

    fun copyLinkUrl(url: String) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        linkContextMenuUrl = null
        Toast.makeText(context, "URLをコピーしました", Toast.LENGTH_SHORT).show()
    }

    fun dismissAlertPrompt() {
        val prompt = pendingAlertPrompt ?: return
        pendingAlertResult?.complete(prompt.dismiss())
        pendingAlertPrompt = null
        pendingAlertResult = null
    }

    fun confirmButtonPrompt(positive: Boolean) {
        val prompt = pendingButtonPrompt ?: return
        val type = if (positive) GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
        else GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
        pendingButtonResult?.complete(prompt.confirm(type))
        pendingButtonPrompt = null
        pendingButtonResult = null
    }

    fun dismissButtonPrompt() {
        val prompt = pendingButtonPrompt ?: return
        pendingButtonResult?.complete(prompt.dismiss())
        pendingButtonPrompt = null
        pendingButtonResult = null
    }

    fun confirmTextPrompt(value: String) {
        val prompt = pendingTextPrompt ?: return
        pendingTextResult?.complete(prompt.confirm(value))
        pendingTextPrompt = null
        pendingTextResult = null
    }

    fun dismissTextPrompt() {
        val prompt = pendingTextPrompt ?: return
        pendingTextResult?.complete(prompt.dismiss())
        pendingTextPrompt = null
        pendingTextResult = null
    }

    fun confirmChoicePromptSingle(choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice) {
        val prompt = pendingChoicePrompt ?: return
        pendingChoiceResult?.complete(prompt.confirm(choice))
        pendingChoicePrompt = null
        pendingChoiceResult = null
    }

    fun confirmChoicePromptMultiple(choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>) {
        val prompt = pendingChoicePrompt ?: return
        pendingChoiceResult?.complete(prompt.confirm(choices))
        pendingChoicePrompt = null
        pendingChoiceResult = null
    }

    fun dismissChoicePrompt() {
        val prompt = pendingChoicePrompt ?: return
        pendingChoiceResult?.complete(prompt.dismiss())
        pendingChoicePrompt = null
        pendingChoiceResult = null
    }

    fun confirmColorPrompt(color: String) {
        val prompt = pendingColorPrompt ?: return
        pendingColorResult?.complete(prompt.confirm(color))
        pendingColorPrompt = null
        pendingColorResult = null
    }

    fun dismissColorPrompt() {
        val prompt = pendingColorPrompt ?: return
        pendingColorResult?.complete(prompt.dismiss())
        pendingColorPrompt = null
        pendingColorResult = null
    }

    fun confirmDateTimePrompt(datetime: String) {
        val prompt = pendingDateTimePrompt ?: return
        pendingDateTimeResult?.complete(prompt.confirm(datetime))
        pendingDateTimePrompt = null
        pendingDateTimeResult = null
    }

    fun dismissDateTimePrompt() {
        val prompt = pendingDateTimePrompt ?: return
        pendingDateTimeResult?.complete(prompt.dismiss())
        pendingDateTimePrompt = null
        pendingDateTimeResult = null
    }

    fun captureTabPreview(geckoView: GeckoView) {
        geckoView.capturePixels().accept(
            { bitmap ->
                val previewBitmap = bitmap ?: return@accept
                coroutineScope.launch(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    previewBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 0, stream)
                    browserTab.previewBitmap = stream.toByteArray()
                }
            },
            {},
        )
    }

    fun flushAndCaptureForTabSwitch(geckoViewRef: GeckoView) {
        session.flushSessionState()
        captureTabPreview(geckoViewRef)
    }

    fun syncTitleToTab() {
        browserTab.title = currentPageTitle
    }

    fun syncUrlToTab() {
        browserTab.currentUrl = currentPageUrl
    }

    // ================================================================
    // Session Delegates
    // ================================================================

    fun createPermissionDelegate(
        onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
    ): GeckoSession.PermissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                return GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                )
            }
            return onDesktopNotificationPermissionRequest(perm.uri)
        }
    }

    fun createNavigationDelegate(
        onOpenNewSessionRequest: (String) -> GeckoSession,
    ): GeckoSession.NavigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, value: Boolean) {
            canGoBack = value
        }

        override fun onCanGoForward(session: GeckoSession, value: Boolean) {
            canGoForward = value
        }

        override fun onNewSession(
            session: GeckoSession,
            uri: String,
        ): GeckoResult<GeckoSession> {
            return GeckoResult.fromValue(onOpenNewSessionRequest(uri))
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val newUrl = url.orEmpty()
            if (newUrl == "about:blank" && currentPageUrl != "about:blank") {
                return
            }
            currentPageUrl = newUrl
            if (!isUrlInputFocused) {
                urlInput = newUrl
            }
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
            // 履歴を記録（about:blank や data: URL は除外）
            if (newUrl.isNotBlank() && !newUrl.startsWith("about:") && !newUrl.startsWith("data:")) {
                currentHistoryEntryId = null
                val callback = onHistoryRecord
                if (callback != null) {
                    coroutineScope.launch {
                        currentHistoryEntryId = callback(newUrl, currentPageTitle)
                    }
                }
            }
        }
    }

    fun createContentDelegate(onClose: (() -> Unit)? = null): GeckoSession.ContentDelegate =
        object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val newTitle = title.orEmpty()
                currentPageTitle = newTitle
                // 履歴エントリのタイトルを更新
                val entryId = currentHistoryEntryId
                val callback = onHistoryTitleUpdate
                if (entryId != null && newTitle.isNotBlank() && callback != null) {
                    coroutineScope.launch {
                        callback(entryId, newTitle)
                    }
                }
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val linkUri = element.linkUri
                if (linkUri != null) {
                    linkContextMenuUrl = linkUri
                    return
                }
                when (element.type) {
                    GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> {
                        imageContextMenuUrl = element.srcUri
                    }

                    GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO,
                    GeckoSession.ContentDelegate.ContextElement.TYPE_NONE,
                    GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> {
                        // TODO
                    }
                }
            }

            override fun onCloseRequest(session: GeckoSession) {
                onClose?.invoke()
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                renderReady = true
            }

            override fun onFirstComposite(session: GeckoSession) {
                renderReady = true
            }

            // GeckoViewがレンダリングできないコンテンツ（ダウンロード対象ファイル等）を受け取った際に呼ばれる
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                downloadFileFromResponse(response)
            }
        }

    fun createProgressDelegate(): GeckoSession.ProgressDelegate =
        object : GeckoSession.ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState,
            ) {
                browserTab.sessionState = sessionState.toString().orEmpty()
            }

            override fun onPageStart(session: GeckoSession, url: String) {
                maybeResetToolbarColorOnPageStart(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                renderReady = true
            }
        }

    fun createTranslationsDelegate(): TranslationsController.SessionTranslation.Delegate =
        object : TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationsController.SessionTranslation.TranslationState?,
            ) {
                val lang = translationState?.detectedLanguages?.docLangTag ?: return
                detectedPageLanguage = lang
            }
        }

    fun createPromptDelegate(): GeckoSession.PromptDelegate =
        object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingAlertPrompt = prompt
                pendingAlertResult = result
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingButtonPrompt = prompt
                pendingButtonResult = result
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingTextPrompt = prompt
                pendingTextResult = result
                return result
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingChoicePrompt = prompt
                pendingChoiceResult = result
                return result
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingColorPrompt = prompt
                pendingColorResult = result
                return result
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingDateTimePrompt = prompt
                pendingDateTimeResult = result
                return result
            }
        }

    // メディアセッションデリゲートを作成
    fun createMediaSessionDelegate(): org.mozilla.geckoview.MediaSession.Delegate {
        return net.matsudamper.browser.media.GeckoMediaSessionDelegate(context)
    }

    fun createScrollDelegate(): GeckoSession.ScrollDelegate =
        object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(
                session: GeckoSession,
                scrollX: Int,
                scrollYValue: Int,
            ) {
                scrollY = scrollYValue
            }
        }

    private fun maybeResetToolbarColor(fromUrl: String, toUrl: String) {
        if (shouldResetToolbarColor(fromUrl, toUrl)) {
            toolbarColor = null
        }
    }

    private fun maybeResetToolbarColorOnPageStart(url: String) {
        val nextKey = normalizedToolbarUrlKey(url)
        if (nextKey == lastPageStartUrlKey) return
        toolbarColor = null
        lastPageStartUrlKey = nextKey
    }

    private fun shouldResetToolbarColor(fromUrl: String, toUrl: String): Boolean {
        return normalizedToolbarUrlKey(fromUrl) != normalizedToolbarUrlKey(toUrl)
    }

    private fun normalizedToolbarUrlKey(url: String): String {
        return url
            .substringBefore("#")
            .removeSuffix("/")
    }
}

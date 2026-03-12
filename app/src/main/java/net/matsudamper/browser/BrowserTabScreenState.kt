package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.TranslationProvider
import org.koin.compose.koinInject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebRequestError
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
    val geckoDownloadManager: GeckoDownloadManager = koinInject()
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
) : BrowserSessionStateCallbacks {
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
    // BrowserTab.themeColor に委譲することで、変更が自動的に永続化対象になる
    var toolbarColor: Color?
        get() = browserTab.themeColor?.let { Color(it) }
        set(value) { browserTab.themeColor = value?.toArgb() }
    private var lastPageStartUrlKey: String = normalizedBrowserPageKey(browserTab.currentUrl)

    // --- Translation state ---
    var translationState by mutableStateOf(TranslationState.Idle)
    var originalPageUrlForRevert by mutableStateOf<String?>(null)
    var detectedPageLanguage by mutableStateOf<String?>(null)

    // --- Find-in-page state ---
    var showFindInPage by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findMatchCurrent by mutableIntStateOf(0)
    var findMatchTotal by mutableIntStateOf(0)

    // --- Context menu state ---
    var imageContextMenuUrl by mutableStateOf<String?>(null)
    var linkContextMenuUrl by mutableStateOf<String?>(null)

    // --- プロンプトダイアログ状態（分離済み） ---
    val promptDialogState = PromptDialogState()

    // --- ファイルダウンロード確認ダイアログ用state ---
    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)

    var renderReady by mutableStateOf(false)
    var pageLoadError by mutableStateOf<PageLoadError?>(null)

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
        clearPageLoadError()
        session.loadUri(resolved)
    }

    fun onHome() {
        urlInput = homepageUrl
        maybeResetToolbarColor(currentPageUrl, homepageUrl)
        currentPageUrl = homepageUrl
        clearPageLoadError()
        session.loadUri(homepageUrl)
    }

    fun onRefresh() {
        refreshCurrentPage()
    }

    fun onRefreshFromSwipe() {
        refreshCurrentPage()
        isRefreshing = false
    }

    fun onGoForward() {
        clearPageLoadError()
        session.goForward()
    }

    fun onGoBack() {
        clearPageLoadError()
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
        refreshCurrentPage()
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
                Log.e("BrowserTabScreenState", "翻訳に失敗しました", result.exceptionOrNull())
                TranslationState.Error
            }
        }
    }

    fun onRevertTranslation() {
        val savedUrl = originalPageUrlForRevert
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        if (savedUrl != null) {
            clearPageLoadError()
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

    fun restoreCurrentPageUrlToInput() {
        urlInput = currentPageUrl
    }

    fun retryPageLoad() {
        refreshCurrentPage()
    }

    fun copyCurrentPageUrl() {
        if (currentPageUrl.isBlank()) return
        copyUrlToClipboard(currentPageUrl)
    }

    fun copyLinkUrl(url: String) {
        copyUrlToClipboard(url)
        linkContextMenuUrl = null
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

    override fun onCanGoBackChanged(value: Boolean) {
        canGoBack = value
    }

    override fun onCanGoForwardChanged(value: Boolean) {
        canGoForward = value
    }

    override fun onLoadError(uri: String?, error: WebRequestError) {
        val resolvedError = error.toPageLoadError(uri)
        val failedUrl = resolvedError.failingUrl
        if (failedUrl.isNotBlank()) {
            maybeResetToolbarColor(currentPageUrl, failedUrl)
            currentPageUrl = failedUrl
            if (!isUrlInputFocused) {
                urlInput = failedUrl
            }
        }
        currentPageTitle = resolvedError.title
        pageLoadError = resolvedError
    }

    override fun onLocationChange(url: String) {
        if (url == "about:blank" && currentPageUrl != "about:blank") {
            return
        }
        if (pageLoadError?.failingUrl != url) {
            clearPageLoadError()
        }
        currentPageUrl = url
        if (!isUrlInputFocused) {
            urlInput = url
        }
        val revertUrl = originalPageUrlForRevert
        if (translationState != TranslationState.Idle &&
            !url.startsWith("data:") &&
            url != revertUrl
        ) {
            translationState = TranslationState.Idle
            originalPageUrlForRevert = null
        }
        if (!url.startsWith("data:")) {
            detectedPageLanguage = null
        }
        // 履歴を記録（about:blank や data: URL は除外）
        if (url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("data:")) {
            currentHistoryEntryId = null
            val callback = onHistoryRecord
            if (callback != null) {
                coroutineScope.launch {
                    currentHistoryEntryId = callback(url, currentPageTitle)
                }
            }
        }
    }

    override fun onTitleChange(title: String) {
        currentPageTitle = title
        val entryId = currentHistoryEntryId
        val callback = onHistoryTitleUpdate
        if (entryId != null && title.isNotBlank() && callback != null) {
            coroutineScope.launch {
                callback(entryId, title)
            }
        }
    }

    override fun onContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
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

    override fun onRenderReady() {
        renderReady = true
    }

    override fun onExternalResponse(response: WebResponse) {
        downloadFileFromResponse(response)
    }

    override fun onSessionStateChange(sessionState: GeckoSession.SessionState) {
        browserTab.sessionState = sessionState.toString().orEmpty()
    }

    override fun onPageStart(url: String) {
        clearPageLoadError()
        maybeResetToolbarColorOnPageStart(url)
    }

    override fun onPageStop(success: Boolean) {
        renderReady = true
    }

    override fun onTranslationStateChange(
        translationState: TranslationsController.SessionTranslation.TranslationState?,
    ) {
        val lang = translationState?.detectedLanguages?.docLangTag ?: return
        detectedPageLanguage = lang
    }

    override fun onScrollChanged(scrollY: Int) {
        this.scrollY = scrollY
    }

    private fun maybeResetToolbarColor(fromUrl: String, toUrl: String) {
        if (net.matsudamper.browser.shouldResetToolbarColor(fromUrl, toUrl)) {
            toolbarColor = null
        }
    }

    private fun refreshCurrentPage() {
        val retryUrl = pageLoadError?.failingUrl?.takeIf { it.isNotBlank() }
        clearPageLoadError()
        if (retryUrl != null) {
            currentPageUrl = retryUrl
            if (!isUrlInputFocused) {
                urlInput = retryUrl
            }
            session.loadUri(retryUrl)
            return
        }
        session.reload()
    }

    private fun clearPageLoadError() {
        pageLoadError = null
    }

    private fun maybeResetToolbarColorOnPageStart(url: String) {
        val nextKey = normalizedBrowserPageKey(url)
        if (nextKey == lastPageStartUrlKey) return
        toolbarColor = null
        lastPageStartUrlKey = nextKey
    }

    private fun copyUrlToClipboard(url: String) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        Toast.makeText(context, "URLをコピーしました", Toast.LENGTH_SHORT).show()
    }
}

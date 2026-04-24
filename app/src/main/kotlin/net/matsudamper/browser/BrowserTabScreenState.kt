package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URL
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.translate.TranslationPriorityLanguage
import org.json.JSONObject
import org.koin.compose.koinInject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayOutputStream


private val PAGE_ZOOM_STEPS = listOf(20, 25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200)

private enum class FindInPageState {
    Closed,
    Normal,
    Regex,
}

@Composable
internal fun rememberBrowserTabScreenState(
    browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
    onRequestDownloadNotificationPermission: suspend () -> Unit = {},
): BrowserTabScreenState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geckoDownloadManager: GeckoDownloadManager = koinInject()
    val findInPageWebExtension: FindInPageWebExtension = koinInject()
    val state = remember(browserTab) {
        BrowserTabScreenState(
            browserTab = browserTab,
            homepageUrl = homepageUrl,
            searchTemplate = searchTemplate,
            coroutineScope = coroutineScope,
            geckoDownloadManager = geckoDownloadManager,
            findInPageWebExtension = findInPageWebExtension,
            context = context,
            onHistoryRecord = onHistoryRecord,
            onHistoryTitleUpdate = onHistoryTitleUpdate,
            onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
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
    internal val findInPageWebExtension: FindInPageWebExtension,
    private val context: Context,
    private val onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    var onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    var onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
) : BrowserSessionStateCallbacks {
    // 現在のページの履歴エントリID（タイトル更新に使用）
    private var currentHistoryEntryId: Long? = null
    // 履歴レコード作成前に届いたタイトルを一時保持する
    private var pendingHistoryTitle: String? = null
    // 遅延して返る履歴レコードIDが古い遷移に紐づくものかを判定する
    private var historyRecordSequence: Long = 0
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
    // フルページロード開始フラグ（SPA の pushState 遷移と区別するため）
    private var isFullPageLoadPending: Boolean = false
    // onLocationChange で履歴記録をスキップする残り回数
    // goBack() / goForward() を複数回連続で呼ぶ場合にもカウンタで対応する
    private var skipHistoryRecordCount: Int = 0
    var webAppManifestJson by mutableStateOf<String?>(null)

    // --- タブ内ナビゲーション履歴（GeckoView の HistoryDelegate から同期） ---
    var tabHistoryItems by mutableStateOf<List<TabHistoryItem>>(emptyList())
    var tabHistoryCurrentIndex by mutableStateOf(-1)

    data class TabHistoryItem(val uri: String, val title: String)

    // --- Translation state ---
    var translationState by mutableStateOf(TranslationState.Idle)
    var originalPageUrlForRevert by mutableStateOf<String?>(null)
    var detectedPageLanguage by mutableStateOf<String?>(null)
    /** 翻訳元言語タグ（例: "en"） */
    var translationFromLanguage by mutableStateOf<String?>(null)
    /** 翻訳先言語タグ（例: "ja"） */
    var translationToLanguage by mutableStateOf<String?>(null)

    // --- Find-in-page state ---
    private var findInPageState by mutableStateOf(FindInPageState.Closed)
    val showFindInPage: Boolean get() = findInPageState != FindInPageState.Closed
    var findQuery by mutableStateOf("")
    var findMatchCurrent by mutableIntStateOf(0)
    var findMatchTotal by mutableIntStateOf(0)
    /** 正規表現モードが有効かどうか */
    val findIsRegex: Boolean get() = findInPageState == FindInPageState.Regex
    /** 無効な正規表現が入力された場合のエラーメッセージ */
    var findQueryError by mutableStateOf<String?>(null)

    // --- Context menu state ---
    var contextMenuState by mutableStateOf<ContextMenuState?>(null)
        private set

    fun dismissContextMenu() {
        contextMenuState = null
    }

    @Stable
    sealed interface ContextMenuState {
        data class Link(val url: String) : ContextMenuState
        data class Image(val srcUrl: String) : ContextMenuState
        data class LinkWithImage(val url: String, val imageSrcUrl: String) : ContextMenuState
    }

    // --- ホームに追加ダイアログ状態 ---
    var addToHomeScreenState by mutableStateOf<AddToHomeScreenState?>(null)
        private set
    private var addToHomeIconJob: Job? = null

    data class AddToHomeScreenState(
        val url: String,
        val title: String,
        val favicon: Bitmap?,
        val isIconLoading: Boolean,
    )

    // --- プロンプトダイアログ状態（分離済み） ---
    val promptDialogState = PromptDialogState(coroutineScope)

    // --- ファイルダウンロード確認ダイアログ用state ---
    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)
    var pendingExternalAppLaunch by mutableStateOf<PendingExternalAppLaunch?>(null)
    // 外部アプリ確認ダイアログでキャンセルされた場合、次回のロードリクエストで外部アプリチェックをスキップする
    private var skipExternalAppCheckForNextLoad = false

    var renderReady by mutableStateOf(false)
    private var previewCaptureReady = false
    var pageLoadError by mutableStateOf<PageLoadError?>(null)

    // --- ズーム状態（viewport width 操作によりテキスト・画像含め全体をズーム）---
    var pageZoomPercent by mutableIntStateOf(100)
        private set

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

    fun onSuperRefresh() {
        // キャッシュをバイパスしてリロード（スーパーリフレッシュ）
        superRefreshCurrentPage()
    }

    fun onRefreshFromSwipe() {
        refreshCurrentPage()
        isRefreshing = false
    }

    fun onGoForward() {
        clearPageLoadError()
        skipHistoryRecordCount++
        if (tabHistoryCurrentIndex < tabHistoryItems.lastIndex) {
            tabHistoryCurrentIndex++
        }
        // 履歴移動先が SPA 同一ドキュメント遷移かフルページロードか不明のため
        // プレビューオーバーレイは表示しない（タブ切り替え・セッション復元時のみ表示）
        session.goForward()
    }

    fun onGoBack() {
        clearPageLoadError()
        skipHistoryRecordCount++
        if (tabHistoryCurrentIndex > 0) {
            tabHistoryCurrentIndex--
        }
        session.goBack()
    }

    /** タブ履歴の指定インデックスへ直接ジャンプする */
    fun jumpToHistoryEntry(targetIndex: Int) {
        if (targetIndex == tabHistoryCurrentIndex) return
        skipHistoryRecordCount++
        tabHistoryCurrentIndex = targetIndex
        session.gotoHistoryIndex(targetIndex)
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

    fun pageZoomIn() {
        val idx = PAGE_ZOOM_STEPS.indexOfLast { it <= pageZoomPercent }
        val next = if (idx < PAGE_ZOOM_STEPS.size - 1) PAGE_ZOOM_STEPS[idx + 1] else PAGE_ZOOM_STEPS.last()
        applyPageZoom(next)
    }

    fun pageZoomOut() {
        val idx = PAGE_ZOOM_STEPS.indexOfFirst { it >= pageZoomPercent }
        val prev = if (idx > 0) PAGE_ZOOM_STEPS[idx - 1] else PAGE_ZOOM_STEPS.first()
        applyPageZoom(prev)
    }

    fun resetPageZoom() {
        applyPageZoom(100)
    }

    private fun applyPageZoom(percent: Int) {
        pageZoomPercent = percent
        injectViewportZoom(percent)
    }

    // viewport meta を書き換えてページ全体のズームを適用する
    // percent=100 のときは width=device-width に戻す
    private fun injectViewportZoom(percent: Int) {
        val viewportContent = if (percent == 100) {
            "width=device-width,initial-scale=1"
        } else {
            val screenWidthDp = (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
            val viewportWidth = screenWidthDp * 100 / percent
            "width=$viewportWidth,initial-scale=1"
        }
        val script = "javascript:void((function(){" +
            "var c='$viewportContent';" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
            "m.content=c;" +
            "})())"
        session.loadUri(script)
    }

    fun openFindInPage() {
        findInPageState = FindInPageState.Normal
    }

    fun closeFindInPage() {
        val previousFindInPageState = findInPageState
        findInPageState = FindInPageState.Closed
        if (previousFindInPageState == FindInPageState.Regex) {
            findInPageWebExtension.clear(session)
        } else {
            session.finder.clear()
        }
        findQuery = ""
        findMatchCurrent = 0
        findMatchTotal = 0
        findQueryError = null
    }

    fun onFindQueryChange(newQuery: String) {
        findQuery = newQuery
        findQueryError = null
        if (newQuery.isEmpty()) {
            if (findIsRegex) {
                findInPageWebExtension.clear(session)
            } else {
                session.finder.clear()
            }
            findMatchCurrent = 0
            findMatchTotal = 0
        } else {
            if (findIsRegex) {
                findInPageWebExtension.search(session, newQuery, isRegex = true)
            } else {
                session.finder.find(newQuery, 0).then<Void?> { result ->
                    findMatchCurrent = result?.current ?: 0
                    findMatchTotal = result?.total ?: 0
                    null
                }
            }
        }
    }

    fun findNext() {
        if (findQuery.isNotEmpty()) {
            if (findIsRegex) {
                findInPageWebExtension.findNext(session)
            } else {
                session.finder.find(findQuery, 0).then<Void?> { result ->
                    findMatchCurrent = result?.current ?: 0
                    findMatchTotal = result?.total ?: 0
                    null
                }
            }
        }
    }

    fun findPrevious() {
        if (findQuery.isNotEmpty()) {
            if (findIsRegex) {
                findInPageWebExtension.findPrevious(session)
            } else {
                session.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS)
                    .then<Void?> { result ->
                        findMatchCurrent = result?.current ?: 0
                        findMatchTotal = result?.total ?: 0
                        null
                    }
            }
        }
    }

    fun toggleFindRegex() {
        val newFindInPageState = if (findInPageState == FindInPageState.Regex) {
            FindInPageState.Normal
        } else {
            FindInPageState.Regex
        }
        findInPageState = newFindInPageState
        findQueryError = null
        if (findQuery.isNotEmpty()) {
            if (newFindInPageState == FindInPageState.Regex) {
                // 通常 → 正規表現: finder をクリアして拡張機能で再検索
                session.finder.clear()
                findInPageWebExtension.search(session, findQuery, isRegex = true)
            } else {
                // 正規表現 → 通常: 拡張機能をクリアして finder で再検索
                findInPageWebExtension.clear(session)
                session.finder.find(findQuery, 0).then<Void?> { result ->
                    findMatchCurrent = result?.current ?: 0
                    findMatchTotal = result?.total ?: 0
                    null
                }
            }
        }
    }

    fun onTranslate(translationProvider: TranslationProvider) {
        if (translationState == TranslationState.Loading) return
        runTranslation(translationProvider, fromLanguage = detectedPageLanguage, toLanguage = TranslationPriorityLanguage.TO)
    }

    /** ステータスバーの言語ドロップダウンから再翻訳を実行する */
    fun onRetranslate(translationProvider: TranslationProvider, fromLanguage: String?, toLanguage: String) {
        if (translationState == TranslationState.Loading) return
        runTranslation(translationProvider, fromLanguage = fromLanguage, toLanguage = toLanguage)
    }

    private fun runTranslation(translationProvider: TranslationProvider, fromLanguage: String?, toLanguage: String) {
        coroutineScope.launch {
            // 初回翻訳時のみ元URLを保存する
            if (originalPageUrlForRevert == null) {
                originalPageUrlForRevert = currentPageUrl
            }
            translationState = TranslationState.Loading
            val pageUrl = originalPageUrlForRevert ?: currentPageUrl
            val result = runCatching {
                PageTranslator(session, pageUrl).translatePage(
                    translationProvider,
                    fromLanguage,
                    toLanguage,
                )
            }
            if (result.isSuccess) {
                val langs = result.getOrNull()
                translationFromLanguage = langs?.fromLanguage
                translationToLanguage = langs?.toLanguage
                translationState = TranslationState.Translated
            } else {
                Log.e("BrowserTabScreenState", "翻訳に失敗しました", result.exceptionOrNull())
                translationFromLanguage = null
                translationToLanguage = null
                translationState = TranslationState.Error
            }
        }
    }

    fun onRevertTranslation() {
        val savedUrl = originalPageUrlForRevert
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        translationFromLanguage = null
        translationToLanguage = null
        if (savedUrl != null) {
            clearPageLoadError()
            session.loadUri(savedUrl)
        }
    }

    fun onDismissTranslationError() {
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        translationFromLanguage = null
        translationToLanguage = null
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
        dismissContextMenu()
        // suspend 前に referrerUrl を確定させる（許可ダイアログ中にページ遷移しても影響を受けないため）
        val referrerUrl = currentPageUrl
        coroutineScope.launch {
            // ダウンロード進捗を通知で表示するためにパーミッションを要求し、ユーザーの応答を待つ
            onRequestDownloadNotificationPermission()
            // WorkManagerにエンキューして通知で進捗表示
            geckoDownloadManager.enqueueDownload(
                url = imageUrl,
                referrerUrl = referrerUrl,
                coroutineScope = coroutineScope,
            )
        }
    }

    // GeckoViewがレンダリングできないレスポンス（ダウンロードリンク等）を受け取った際に呼ばれる
    // ユーザーに確認ダイアログを表示するため、pendingDownloadResponseに保持する
    fun downloadFileFromResponse(response: WebResponse) {
        pendingDownloadResponse = response
    }

    fun confirmPendingDownload() {
        val response = pendingDownloadResponse ?: return
        pendingDownloadResponse = null
        // suspend 前に必要な情報を取り出し、body は即座にクローズする
        // （許可ダイアログ中にキャンセルされても body がリークしないようにするため）
        val downloadUrl = response.uri
        response.body?.close()
        val referrerUrl = currentPageUrl
        coroutineScope.launch {
            // ダウンロード進捗を通知で表示するためにパーミッションを要求し、ユーザーの応答を待つ
            onRequestDownloadNotificationPermission()
            geckoDownloadManager.enqueueDownload(
                url = downloadUrl,
                referrerUrl = referrerUrl,
                coroutineScope = coroutineScope,
            )
        }
    }

    fun dismissPendingDownload() {
        pendingDownloadResponse?.body?.close()
        pendingDownloadResponse = null
    }

    fun confirmPendingExternalAppLaunch() {
        val request = pendingExternalAppLaunch ?: return
        pendingExternalAppLaunch = null
        val result = launchExternalApp(context, request)
        if (result.isSuccess) {
            return
        }

        val fallbackUrl = request.fallbackUrl
        if (fallbackUrl != null) {
            openFallbackUrl(fallbackUrl)
            return
        }
        Toast.makeText(context, "対応するアプリを開けませんでした", Toast.LENGTH_SHORT).show()
    }

    fun dismissPendingExternalAppLaunch() {
        pendingExternalAppLaunch = null
    }

    /**
     * 外部アプリ確認ダイアログでキャンセルされた際に、
     * ブラウザ内で（deep linkではなく）URLを読み込む。
     * http/https の場合は sourceUri をそのまま使い、
     * intent:// 等のカスタムスキームの場合は fallbackUrl を使用する。
     */
    fun dismissPendingExternalAppLaunchAndLoadInBrowser() {
        val request = pendingExternalAppLaunch ?: return
        pendingExternalAppLaunch = null

        val url = if (request.sourceUri.startsWith("http://") || request.sourceUri.startsWith("https://")) {
            request.sourceUri
        } else {
            request.fallbackUrl
        }
        if (url != null) {
            skipExternalAppCheckForNextLoad = true
            openFallbackUrl(url)
        }
    }

    fun restoreCurrentPageUrlToInput() {
        urlInput = currentPageUrl
    }

    fun retryPageLoad() {
        refreshCurrentPage()
    }

    fun requestAddToHomeScreen() {
        val pageUrl = currentPageUrl
        val pageTitle = currentPageTitle
        val manifestJson = webAppManifestJson
        val fallbackFavicon = browserTab.faviconBitmap
        addToHomeIconJob?.cancel()
        addToHomeScreenState = AddToHomeScreenState(
            url = pageUrl,
            title = pageTitle,
            favicon = null,
            isIconLoading = true,
        )
        addToHomeIconJob = coroutineScope.launch {
            // CancellationException は runCatching で握りつぶさずに呼び出し側へ伝播させる。
            // 同一URLで requestAddToHomeScreen() を再送した際、旧ジョブの cancel() 後に
            // このコルーチンが継続して新リクエストの isIconLoading=false を書き戻すのを防ぐ。
            val fetchedIcon = try {
                HomeScreenIconFetcher.fetchIcon(
                    pageUrl = pageUrl,
                    webAppManifestJson = manifestJson,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            try {
                val current = addToHomeScreenState ?: return@launch
                if (current.url != pageUrl) return@launch
                addToHomeScreenState = current.copy(
                    favicon = fetchedIcon ?: fallbackFavicon,
                    isIconLoading = false,
                )
                if (fetchedIcon != null && currentPageUrl == pageUrl) {
                    browserTab.faviconBitmap = fetchedIcon
                }
            } finally {
                // 予期せぬ例外でもスピナー表示が残らないようロード中状態を必ず解除する
                val current = addToHomeScreenState
                if (current != null && current.url == pageUrl && current.isIconLoading) {
                    addToHomeScreenState = current.copy(
                        favicon = current.favicon ?: fallbackFavicon,
                        isIconLoading = false,
                    )
                }
            }
        }
    }

    fun dismissAddToHomeScreen() {
        addToHomeIconJob?.cancel()
        addToHomeIconJob = null
        addToHomeScreenState = null
    }

    fun copyCurrentPageUrl() {
        if (currentPageUrl.isBlank()) return
        copyUrlToClipboard(currentPageUrl)
    }

    fun copyLinkUrl(url: String) {
        copyUrlToClipboard(url)
        dismissContextMenu()
    }

    fun captureTabPreview(geckoView: GeckoView, onCaptured: (() -> Unit)? = null) {
        if (!shouldCaptureTabPreview(previewCaptureReady)) {
            onCaptured?.invoke()
            return
        }
        geckoView.capturePixels().accept(
            { bitmap ->
                val previewBitmap = bitmap ?: run {
                    onCaptured?.invoke()
                    return@accept
                }
                // ビットマップ取得済みのため、セッションリリースはこの後でも問題ない
                onCaptured?.invoke()
                coroutineScope.launch(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    previewBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 0, stream)
                    browserTab.previewBitmap = stream.toByteArray()
                }
            },
            { onCaptured?.invoke() },
        )
    }

    fun flushAndCaptureForTabSwitch(geckoViewRef: GeckoView) {
        session.flushSessionState()
        captureTabPreview(geckoViewRef)
    }

    override fun onCanGoBackChanged(value: Boolean) {
        canGoBack = value
    }

    override fun onCanGoForwardChanged(value: Boolean) {
        canGoForward = value
    }

    override fun onHistoryStateChange(items: List<HistoryStateItem>, currentIndex: Int) {
        tabHistoryItems = items.map { TabHistoryItem(uri = it.uri, title = it.title) }
        tabHistoryCurrentIndex = currentIndex
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
        // フルページロード（onPageStart が先行した場合）のみ色をリセット
        // SPA 遷移（pushState）では onPageStart が発火しないためリセットしない
        // ダウンロードリンクのように onPageStart だけ発火して onLocationChange が呼ばれない
        // ケースは isFullPageLoadPending が onPageStop でクリアされるため色をリセットしない
        if (isFullPageLoadPending) {
            maybeResetToolbarColorOnPageStart(url)
            isFullPageLoadPending = false
        } else {
            // SPA 遷移（pushState / 同一ドキュメント内 history 移動）では
            // onPageStart / onPageStop が発火しないため両フラグを復帰させる
            markRenderingDone()
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
        // goBack / goForward 時はカウンタをデクリメントしてスキップする
        val shouldRecord = url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("data:")
        val skip = skipHistoryRecordCount > 0
        if (skip) skipHistoryRecordCount--
        if (shouldRecord && !skip) {
            // onHistoryStateChange の発火は遅延するため、楽観的にタブ履歴を更新する
            val newItem = TabHistoryItem(uri = url, title = "")
            tabHistoryItems = tabHistoryItems.take(tabHistoryCurrentIndex + 1) + newItem
            tabHistoryCurrentIndex = tabHistoryItems.lastIndex

            historyRecordSequence++
            val sequence = historyRecordSequence
            currentHistoryEntryId = null
            pendingHistoryTitle = null
            val callback = onHistoryRecord
            if (callback != null) {
                coroutineScope.launch {
                    val historyEntryId = callback(url, "")
                    if (historyRecordSequence != sequence) {
                        return@launch
                    }
                    currentHistoryEntryId = historyEntryId
                    applyPendingHistoryTitle(historyEntryId)
                }
            }
        }
    }

    override fun onTitleChange(title: String) {
        currentPageTitle = title
        val entryId = currentHistoryEntryId
        val callback = onHistoryTitleUpdate
        if (tabHistoryCurrentIndex in tabHistoryItems.indices) {
            tabHistoryItems = tabHistoryItems.toMutableList().apply {
                this[tabHistoryCurrentIndex] = this[tabHistoryCurrentIndex].copy(title = title)
            }
        }
        if (entryId != null && title.isNotBlank() && callback != null) {
            coroutineScope.launch {
                callback(entryId, title)
            }
        } else if (title.isNotBlank()) {
            pendingHistoryTitle = title
        }
    }

    override fun onWebAppManifest(manifest: JSONObject) {
        webAppManifestJson = manifest.toString()
    }

    override fun onContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
        val linkUri = element.linkUri
        val srcUri = element.srcUri
        val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
        contextMenuState = when {
            linkUri != null && isImage && srcUri != null ->
                ContextMenuState.LinkWithImage(url = linkUri, imageSrcUrl = srcUri)
            linkUri != null -> ContextMenuState.Link(url = linkUri)
            isImage && srcUri != null -> ContextMenuState.Image(srcUrl = srcUri)
            // AUDIO / VIDEO / NONE は未対応
            else -> null
        }
    }

    override fun onRenderReady() {
        renderReady = true
    }

    override fun onPreviewCaptureReady() {
        renderReady = true
        previewCaptureReady = true
    }

    override fun onExternalResponse(response: WebResponse) {
        downloadFileFromResponse(response)
    }

    override fun onSessionStateChange(sessionState: GeckoSession.SessionState) {
    }

    override fun onPageStart(url: String) {
        clearPageLoadError()
        previewCaptureReady = false
        // 新しいページへの遷移時にfaviconをリセット
        browserTab.faviconBitmap = null
        webAppManifestJson = null
        isFullPageLoadPending = true
    }

    override fun onPageStop(success: Boolean) {
        // ダウンロードリンク等で onLocationChange が来ない場合のフラグをクリア
        isFullPageLoadPending = false
        renderReady = true
        if (success) {
            fetchFavicon(currentPageUrl)
            // ページ遷移後もズームを維持する
            if (pageZoomPercent != 100) {
                injectViewportZoom(pageZoomPercent)
            }
        }
    }

    /**
     * ページのfaviconを非同期でフェッチしてBrowserTabに保存する。
     * <origin>/favicon.ico を試みる。失敗した場合はnullのままにする。
     */
    private fun fetchFavicon(pageUrl: String) {
        val uri = runCatching { java.net.URI(pageUrl) }.getOrNull() ?: return
        val scheme = uri.scheme ?: return
        if (scheme != "http" && scheme != "https") return
        val host = uri.host ?: return
        val faviconUrl = "$scheme://$host/favicon.ico"
        coroutineScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val connection = URL(faviconUrl).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()
                    connection.inputStream.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            // ナビゲーション後の古いfetchが後から完了しても上書きしないようにチェック
            if (bitmap != null && currentPageUrl == pageUrl) {
                browserTab.faviconBitmap = bitmap
            }
        }
    }

    override fun onLoadRequest(
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny>? {
        if (skipExternalAppCheckForNextLoad) {
            skipExternalAppCheckForNextLoad = false
            return null
        }
        return when (val action = resolveExternalAppNavigationAction(context, request.uri)) {
            ExternalAppNavigationAction.AllowInBrowser -> null
            ExternalAppNavigationAction.AppNotFound -> {
                Toast.makeText(context, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show()
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            is ExternalAppNavigationAction.Launch -> {
                pendingExternalAppLaunch = action.request
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            is ExternalAppNavigationAction.OpenFallback -> {
                openFallbackUrl(action.url)
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }
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

    private fun superRefreshCurrentPage() {
        // キャッシュを完全にバイパスして再読み込みする
        val retryUrl = pageLoadError?.failingUrl?.takeIf { it.isNotBlank() }
        clearPageLoadError()
        if (retryUrl != null) {
            currentPageUrl = retryUrl
            if (!isUrlInputFocused) {
                urlInput = retryUrl
            }
            session.load(
                GeckoSession.Loader()
                    .uri(retryUrl)
                    .flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE),
            )
            return
        }
        session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    private fun clearPageLoadError() {
        pageLoadError = null
    }

    private fun applyPendingHistoryTitle(entryId: Long) {
        val title = pendingHistoryTitle
        val callback = onHistoryTitleUpdate
        if (title.isNullOrBlank() || callback == null) {
            return
        }
        pendingHistoryTitle = null
        coroutineScope.launch {
            callback(entryId, title)
        }
    }

    private fun maybeResetToolbarColorOnPageStart(url: String) {
        val nextKey = normalizedBrowserPageKey(url)
        if (nextKey == lastPageStartUrlKey) return
        toolbarColor = null
        lastPageStartUrlKey = nextKey
    }

    private fun openFallbackUrl(url: String) {
        maybeResetToolbarColor(currentPageUrl, url)
        currentPageUrl = url
        if (!isUrlInputFocused) {
            urlInput = url
        }
        clearPageLoadError()
        session.loadUri(url)
    }

    private fun markRenderingDone() {
        renderReady = true
        previewCaptureReady = true
    }

    private fun copyUrlToClipboard(url: String) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        Toast.makeText(context, "URLをコピーしました", Toast.LENGTH_SHORT).show()
    }
}

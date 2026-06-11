package net.matsudamper.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URL
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.extractSiteHost
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


private const val TAG = "BrowserTabScreenState"

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
    isSinglePageMode: Boolean = false,
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
    onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    onRequestAndroidPermissions: suspend (Array<String>) -> Array<String> = { emptyArray() },
): BrowserTabScreenState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geckoDownloadManager: GeckoDownloadManager = koinInject()
    val findInPageWebExtension: FindInPageWebExtension = koinInject()
    val siteSettingsRepository: SiteSettingsRepository = koinInject()
    val state = remember(browserTab) {
        BrowserTabScreenState(
            browserTab = browserTab,
            homepageUrl = homepageUrl,
            searchTemplate = searchTemplate,
            isSinglePageMode = isSinglePageMode,
            coroutineScope = coroutineScope,
            geckoDownloadManager = geckoDownloadManager,
            findInPageWebExtension = findInPageWebExtension,
            siteSettingsRepository = siteSettingsRepository,
            context = context,
            onHistoryRecord = onHistoryRecord,
            onHistoryTitleUpdate = onHistoryTitleUpdate,
            onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
            onRequestAndroidPermissions = onRequestAndroidPermissions,
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
    private val isSinglePageMode: Boolean = false,
    private val coroutineScope: CoroutineScope,
    private val geckoDownloadManager: GeckoDownloadManager,
    internal val findInPageWebExtension: FindInPageWebExtension,
    private val siteSettingsRepository: SiteSettingsRepository,
    private val context: Context,
    private val onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    private val onRequestAndroidPermissions: suspend (Array<String>) -> Array<String> = { emptyArray() },
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
    private var translationJob: Job? = null

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

    // --- サイトごとのマイク許可確認ダイアログ状態 ---
    var microphonePermissionDialog by mutableStateOf<MicrophonePermissionDialogState?>(null)
        private set

    /**
     * @param onResult true=許可(永続化), false=ブロック(永続化), null=今回のみ拒否
     */
    @Stable
    class MicrophonePermissionDialogState(
        val host: String,
        internal val onResult: (Boolean?) -> Unit,
    )

    fun confirmMicrophonePermissionDialog(allow: Boolean) {
        val dialog = microphonePermissionDialog ?: return
        microphonePermissionDialog = null
        dialog.onResult(allow)
    }

    fun dismissMicrophonePermissionDialog() {
        val dialog = microphonePermissionDialog ?: return
        microphonePermissionDialog = null
        dialog.onResult(null)
    }

    /**
     * サイトごとのマイク権限を解決する。
     * 未設定 (ASK) の場合は確認ダイアログを表示してユーザーの応答を待ち、
     * 許可/ブロックの選択をサイト設定として永続化する。
     */
    private suspend fun resolveMicrophonePermission(host: String): Boolean {
        // 要求があったことを記録し、「サイトの設定」画面にマイクの項目を表示できるようにする
        siteSettingsRepository.markMicrophonePermissionRequested(host)
        when (siteSettingsRepository.getMicrophonePermission(host)) {
            SitePermissionState.SITE_PERMISSION_ALLOW -> return true
            SitePermissionState.SITE_PERMISSION_DENY -> return false
            else -> Unit
        }
        // 表示中のダイアログが残っている場合は今回のみ拒否として閉じる
        microphonePermissionDialog?.also { previous ->
            microphonePermissionDialog = null
            previous.onResult(null)
        }
        val result = CompletableDeferred<Boolean?>()
        microphonePermissionDialog = MicrophonePermissionDialogState(host) { allow ->
            result.complete(allow)
        }
        val choice = result.await()
        if (choice != null) {
            siteSettingsRepository.setMicrophonePermission(
                host = host,
                state = if (choice) {
                    SitePermissionState.SITE_PERMISSION_ALLOW
                } else {
                    SitePermissionState.SITE_PERMISSION_DENY
                },
            )
        }
        return choice == true
    }

    // --- ファイルダウンロード確認ダイアログ用state ---
    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)
    var pendingExternalAppLaunch by mutableStateOf<PendingExternalAppLaunch?>(null)
    // 外部アプリ確認ダイアログでキャンセルされた場合、次回のロードリクエストで外部アプリチェックをスキップする
    private var skipExternalAppCheckForNextLoad = false

    var renderReady by mutableStateOf(false)

    // プレビュー画像が未取得の状態でページロードが完了した際にインクリメントされるカウンター。
    // GeckoBrowserTab がこの値を監視してキャプチャをトリガーする。
    var captureOnPageLoadRequestCount by mutableIntStateOf(0)
        private set

    // プレビューキャプチャの可否を表すフラグ。
    // false の間は captureTabPreview() が早期 return するため、状態遷移が
    // 想定通りに行われないと「いつまで経ってもプレビューが保存されない」状態に
    // 陥る。デバッグしやすいよう、すべての遷移を理由付きでログに残す。
    //
    // 設計判断:
    // - 初期値は「過去にプレビューが保存されているか」または「セッション状態が
    //   復元される予定か」を基準に true にする。プロセス再起動時に、復元タブで
    //   onPageStart/onPageStop が発火しないままタブ切替が走ると永遠に false の
    //   ままになる問題を防ぐ。
    // - onPageStart では false に戻さない。GeckoView は新ページのロード中も
    //   古いページを表示し続けるため、ロード中にキャプチャしても白ページには
    //   ならない。一方、ロードが完了せずに外部アプリ遷移・ダウンロード判定・
    //   ナビゲーションキャンセル等が起きると onPageStop が発火せずフラグが
    //   false のまま固まる問題があった。
    private var previewCaptureReady: Boolean =
        browserTab.previewBitmap?.isNotEmpty() == true || browserTab.sessionState.isNotBlank()
        set(value) {
            if (field == value) return
            Log.d(
                TAG,
                "previewCaptureReady ${field} -> $value (tabId=${browserTab.tabId} url=$currentPageUrl)",
            )
            field = value
        }

    init {
        Log.d(
            TAG,
            "init previewCaptureReady=$previewCaptureReady (tabId=${browserTab.tabId} hasPreview=${browserTab.previewBitmap?.isNotEmpty() == true} hasSessionState=${browserTab.sessionState.isNotBlank()})",
        )
    }
    var pageLoadError by mutableStateOf<PageLoadError?>(null)

    // --- ズーム状態（viewport width 操作によりテキスト・画像含め全体をズーム）---
    var pageZoomPercent by mutableIntStateOf(100)
        private set

    // --- Scroll / Refresh state ---
    var visualViewportScale by mutableFloatStateOf(1f)
    // ピンチジェスチャー検出フラグ。JS管理のズーム（Xの画像ビューアー等）では
    // visualViewport.scaleが1.0のままなので、ピンチ操作自体を記録して
    // 後続のパンジェスチャーでPullToRefreshが誤発動しないようにする。
    var hadPinchGesture by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
    // BrowserTab.scrollY に委譲することで、タブ切替で State が再生成されても
    // スクロール位置を保持し、復元タブでの PullToRefresh 誤発動を防ぐ。
    var scrollY: Int
        get() = browserTab.scrollY
        set(value) { browserTab.scrollY = value }

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
        when (translationState) {
            TranslationState.Idle -> {
                runTranslation(
                    translationProvider,
                    fromLanguage = detectedPageLanguage,
                    toLanguage = TranslationPriorityLanguage.TO,
                )
            }

            TranslationState.Loading,
            TranslationState.Translated -> {
                closeTranslationBar(revertPage = true)
            }

            TranslationState.Error -> {
                closeTranslationBar(revertPage = false)
            }
        }
    }

    /** ステータスバーの言語ドロップダウンから再翻訳を実行する */
    fun onRetranslate(translationProvider: TranslationProvider, fromLanguage: String?, toLanguage: String) {
        if (translationState == TranslationState.Loading) return
        runTranslation(translationProvider, fromLanguage = fromLanguage, toLanguage = toLanguage)
    }

    private fun runTranslation(translationProvider: TranslationProvider, fromLanguage: String?, toLanguage: String) {
        translationJob?.cancel()
        translationJob = coroutineScope.launch {
            // 初回翻訳時のみ元URLを保存する
            if (originalPageUrlForRevert == null) {
                originalPageUrlForRevert = currentPageUrl
            }
            // 非同期処理完了後にページ遷移済みかを検出するために翻訳開始時のURLを保持する
            val translationStartUrl = originalPageUrlForRevert
            translationState = TranslationState.Loading
            val pageUrl = translationStartUrl ?: currentPageUrl
            val result = runCatching {
                PageTranslator(session, pageUrl).translatePage(
                    translationProvider,
                    fromLanguage,
                    toLanguage,
                )
            }
            // CancellationException は runCatching で握りつぶさずに伝播させる。
            // キャンセル済みジョブが新ジョブの状態を上書きするのを防ぐ。
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            // 翻訳中にページ遷移が発生した場合（onLocationChange が originalPageUrlForRevert をクリア済み）は
            // 翻訳結果を破棄して翻訳バーを表示しない
            if (originalPageUrlForRevert != translationStartUrl) return@launch
            if (result.isSuccess) {
                val langs = result.getOrNull()
                translationFromLanguage = langs?.fromLanguage
                translationToLanguage = langs?.toLanguage
                translationState = TranslationState.Translated
            } else {
                Log.e(TAG, "翻訳に失敗しました", result.exceptionOrNull())
                translationFromLanguage = null
                translationToLanguage = null
                translationState = TranslationState.Error
            }
        }
    }

    fun onRevertTranslation() {
        val savedUrl = originalPageUrlForRevert
        closeTranslationBar(revertPage = false)
        if (savedUrl != null) {
            clearPageLoadError()
            session.loadUri(savedUrl)
        }
    }

    fun onDismissTranslationError() {
        closeTranslationBar(revertPage = false)
    }

    private fun closeTranslationBar(revertPage: Boolean) {
        translationJob?.cancel()
        translationJob = null
        val savedUrl = originalPageUrlForRevert
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        translationFromLanguage = null
        translationToLanguage = null
        if (revertPage && savedUrl != null) {
            clearPageLoadError()
            session.loadUri(savedUrl)
        }
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
        // body はクローズせず、Worker がボディ（実データ）を直接保存できるよう response ごと引き渡す。
        // パスワード submit(POST)・ワンタイムURL のダウンロードを 0 バイトにしないため。
        val referrerUrl = currentPageUrl
        coroutineScope.launch {
            var enqueued = false
            try {
                // ダウンロード進捗を通知で表示するためにパーミッションを要求し、ユーザーの応答を待つ
                onRequestDownloadNotificationPermission()
                geckoDownloadManager.enqueueDownloadFromResponse(
                    response = response,
                    referrerUrl = referrerUrl,
                    coroutineScope = coroutineScope,
                )
                enqueued = true
            } finally {
                // 権限待ち中などにキャンセルされ、エンキューに到達しなかった場合はボディを閉じてリークを防ぐ
                if (!enqueued) {
                    response.body?.close()
                }
            }
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
            Log.d(TAG, "captureTabPreview skipped: previewCaptureReady=false (tabId=${browserTab.tabId} url=$currentPageUrl)")
            onCaptured?.invoke()
            return
        }
        Log.d(TAG, "captureTabPreview start (tabId=${browserTab.tabId} url=$currentPageUrl)")
        geckoView.capturePixels().accept(
            { bitmap ->
                val previewBitmap = bitmap ?: run {
                    Log.w(TAG, "capturePixels returned null bitmap (tabId=${browserTab.tabId})")
                    onCaptured?.invoke()
                    return@accept
                }
                // ビットマップ取得済みのため、セッションリリースはこの後でも問題ない
                onCaptured?.invoke()
                // coroutineScope はタブ切替ナビゲーション直後に Composable が composition から
                // 外れるとキャンセルされる。compress〜保存は独立したスコープで完走させる。
                val tabIdForLog = browserTab.tabId
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    if (previewBitmap.isRecycled) {
                        Log.w(TAG, "previewBitmap recycled before compress (tabId=$tabIdForLog)")
                        return@launch
                    }
                    // HARDWARE configはcompress()できないためソフトウェアBitmapにコピーする
                    // copy()はメモリ不足時にnullを返す（例外ではない）
                    val copiedBitmap: Bitmap? = if (previewBitmap.config == Bitmap.Config.HARDWARE) {
                        runCatching { previewBitmap.copy(Bitmap.Config.ARGB_8888, false) }
                            .getOrElse {
                                Log.e(TAG, "HARDWARE copy threw (tabId=$tabIdForLog)", it)
                                return@launch
                            }
                            ?: run {
                                Log.e(TAG, "HARDWARE copy returned null (tabId=$tabIdForLog)")
                                return@launch
                            }
                    } else {
                        null
                    }
                    val sourceBitmap = copiedBitmap ?: previewBitmap
                    val stream = ByteArrayOutputStream()
                    val success = runCatching {
                        sourceBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, stream)
                    }.getOrElse {
                        Log.e(TAG, "compress threw (tabId=$tabIdForLog)", it)
                        false
                    }
                    copiedBitmap?.recycle()
                    val bytes = stream.toByteArray()
                    if (!success || bytes.isEmpty()) {
                        Log.w(TAG, "compress failed success=$success size=${bytes.size} (tabId=$tabIdForLog)")
                        return@launch
                    }
                    browserTab.previewBitmap = bytes
                    Log.d(TAG, "previewBitmap saved size=${bytes.size} (tabId=$tabIdForLog)")
                }
            },
            { error ->
                Log.w(TAG, "capturePixels error (tabId=${browserTab.tabId})", error)
                onCaptured?.invoke()
            },
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
        if (url.startsWith("javascript:")) return
        hadPinchGesture = false
        visualViewportScale = 1f
        if (pageLoadError?.failingUrl != url) {
            clearPageLoadError()
        }
        // フルページロード（onPageStart が先行した場合）のみ色をリセット
        // SPA 遷移（pushState）では onPageStart が発火しないためリセットしない
        // ダウンロードリンクのように onPageStart だけ発火して onLocationChange が呼ばれない
        // ケースは isFullPageLoadPending が onPageStop でクリアされるため色をリセットしない
        val wasFullPageLoad = isFullPageLoadPending
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
        if (shouldResetTranslationOnLocationChange(translationState, url, originalPageUrlForRevert, wasFullPageLoad)) {
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
        hadPinchGesture = false
        visualViewportScale = 1f
        // previewCaptureReady は false に戻さない。
        // GeckoView は新ページの描画が始まるまで古いページを表示し続けるため、
        // ロード中のキャプチャは古いページの画像となり問題ない。
        // 一方、外部アプリ遷移・ダウンロード判定・onLoadRequest DENY 等で
        // onPageStop が発火しないケースで flag が false のまま固まる問題を回避する。
        // 新しいページへの遷移時にfaviconをリセット
        browserTab.faviconBitmap = null
        webAppManifestJson = null
        isFullPageLoadPending = true
    }

    override fun onPageStop(success: Boolean) {
        // ダウンロードリンク等で onLocationChange が来ない場合のフラグをクリア
        isFullPageLoadPending = false
        renderReady = true
        // onFirstContentfulPaint が発火しないページ（エラー、リダイレクト、キャッシュ等）でも
        // ページロード完了時点でキャプチャを許可する。これがないと previewCaptureReady が
        // false のまま戻らず、以降のタブのキャプチャが全て拒否される。
        previewCaptureReady = true
        // プレビュー画像がまだない場合はページロード完了時にキャプチャをリクエストする
        if (browserTab.previewBitmap == null || browserTab.previewBitmap!!.isEmpty()) {
            captureOnPageLoadRequestCount++
        }
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
        val externalAction = resolveExternalAppNavigationAction(context, request.uri)
        // single-page モードで TARGET_WINDOW_NEW は外部アプリ判定を先に通してから現在タブへ畳み込む
        if (isSinglePageMode && request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
            when (externalAction) {
                ExternalAppNavigationAction.AllowInBrowser -> {
                    val uri = request.uri
                    if (uri.isNotBlank() && uri != "about:blank") {
                        session.loadUri(uri)
                    }
                }
                ExternalAppNavigationAction.AppNotFound -> {
                    Toast.makeText(context, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show()
                }
                is ExternalAppNavigationAction.Launch -> {
                    pendingExternalAppLaunch = externalAction.request
                }
                is ExternalAppNavigationAction.OpenFallback -> {
                    openFallbackUrl(externalAction.url)
                }
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }
        return when (externalAction) {
            ExternalAppNavigationAction.AllowInBrowser -> null
            ExternalAppNavigationAction.AppNotFound -> {
                Toast.makeText(context, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show()
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            is ExternalAppNavigationAction.Launch -> {
                pendingExternalAppLaunch = externalAction.request
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            is ExternalAppNavigationAction.OpenFallback -> {
                openFallbackUrl(externalAction.url)
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

    override fun onAndroidPermissionsRequest(
        permissions: Array<String>?,
        onGrant: () -> Unit,
        onReject: () -> Unit,
    ) {
        val perms = permissions ?: run { onReject(); return }
        coroutineScope.launch {
            // OS の権限要求の前に、サイトごとのマイク許可を確認する
            if (Manifest.permission.RECORD_AUDIO in perms) {
                val host = extractSiteHost(currentPageUrl)
                if (host == null || !resolveMicrophonePermission(host)) {
                    onReject()
                    return@launch
                }
            }
            runCatching {
                onRequestAndroidPermissions(perms)
            }.onSuccess { granted ->
                if (perms.all { it in granted }) onGrant() else onReject()
            }.onFailure {
                onReject()
            }
        }
    }

    override fun onMediaPermissionRequest(
        uri: String,
        hasVideo: Boolean,
        hasAudio: Boolean,
        onResult: (grantVideo: Boolean, grantAudio: Boolean) -> Unit,
    ) {
        // マイクを含まない要求（カメラのみ等）は従来通り許可する
        if (!hasAudio) {
            onResult(hasVideo, false)
            return
        }
        coroutineScope.launch {
            // OS 権限が許可済みの場合は onAndroidPermissionsRequest を経由しないため、
            // ここでもサイトごとのマイク許可を確認する（未設定ならダイアログを表示する）
            val host = extractSiteHost(uri) ?: extractSiteHost(currentPageUrl)
            val grantAudio = host != null && resolveMicrophonePermission(host)
            onResult(hasVideo, grantAudio)
        }
    }

    override fun onGeolocationPermissionRequest(
        uri: String?,
        onResult: (allow: Boolean) -> Unit,
    ) {
        // Gecko の GeckoResult を未解決のまま残さないよう、onResult は必ず一度呼ぶ
        val completed = AtomicBoolean(false)
        val job = coroutineScope.launch {
            // モック/拒否はコンテンツスクリプトが処理するため、Gecko 本体の位置情報は
            // サイトごとの設定が「実際の位置情報」の場合のみ許可する
            val host = uri?.let { extractSiteHost(it) } ?: extractSiteHost(currentPageUrl)
            val allow = host != null &&
                runCatching { siteSettingsRepository.getGeolocationState(host) }.getOrNull() ==
                SiteGeolocationState.SITE_GEOLOCATION_REAL
            if (completed.compareAndSet(false, true)) {
                onResult(allow)
            }
        }
        job.invokeOnCompletion { cause ->
            // スコープのキャンセル等で onResult まで到達しなかった場合は拒否として完了させる
            if (cause != null && completed.compareAndSet(false, true)) {
                onResult(false)
            }
        }
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

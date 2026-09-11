package net.matsudamper.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ScrollState
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
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.extractSiteHost
import net.matsudamper.browser.download.proceedDownloadFromResponse
import net.matsudamper.browser.feature.devtools.DevToolsWebExtension
import net.matsudamper.browser.feature.findinpage.FindInPageWebExtension
import net.matsudamper.browser.translate.TranslationPriorityLanguage
import net.matsudamper.browser.ui.browser.BrowserScreenUiState
import org.json.JSONObject
import org.koin.compose.koinInject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

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
    webAppPinnedHost: String? = null,
    onWebAppCrossDomainNavigation: ((String) -> Unit)? = null,
    onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
    onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    onRequestAndroidPermissions: suspend (Array<String>) -> Array<String> = { emptyArray() },
    externalDownloadDialogListener: BrowserScreenUiState.ExternalDownloadDialogListener? = null,
    externalTabInitialUrl: String? = null,
): BrowserTabScreenState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geckoDownloadManager: GeckoDownloadManager = koinInject()
    val findInPageWebExtension: FindInPageWebExtension = koinInject()
    val devToolsWebExtension: DevToolsWebExtension = koinInject()
    val siteSettingsRepository: SiteSettingsRepository = koinInject()
    val settingsRepository: SettingsRepository = koinInject()
    val webExtensionActionController: WebExtensionActionController = koinInject()
    val state = remember(browserTab) {
        BrowserTabScreenState(
            browserTab = browserTab,
            homepageUrl = homepageUrl,
            searchTemplate = searchTemplate,
            isSinglePageMode = isSinglePageMode,
            webAppPinnedHost = webAppPinnedHost,
            onWebAppCrossDomainNavigation = onWebAppCrossDomainNavigation,
            coroutineScope = coroutineScope,
            geckoDownloadManager = geckoDownloadManager,
            findInPageWebExtension = findInPageWebExtension,
            devToolsWebExtension = devToolsWebExtension,
            siteSettingsRepository = siteSettingsRepository,
            settingsRepository = settingsRepository,
            webExtensionActionController = webExtensionActionController,
            context = context,
            onHistoryRecord = onHistoryRecord,
            onHistoryTitleUpdate = onHistoryTitleUpdate,
            onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
            onRequestAndroidPermissions = onRequestAndroidPermissions,
            externalDownloadDialogListener = externalDownloadDialogListener,
            externalTabInitialUrl = externalTabInitialUrl,
        )
    }
    state.homepageUrl = homepageUrl
    state.searchTemplate = searchTemplate
    state.webAppPinnedHost = webAppPinnedHost
    state.onWebAppCrossDomainNavigation = onWebAppCrossDomainNavigation
    state.onHistoryRecord = onHistoryRecord
    state.onHistoryTitleUpdate = onHistoryTitleUpdate
    state.externalDownloadDialogListener = externalDownloadDialogListener
    state.externalTabInitialUrl = externalTabInitialUrl
    return state
}

@Stable
internal class BrowserTabScreenState(
    val browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    private val isSinglePageMode: Boolean = false,
    webAppPinnedHost: String? = null,
    onWebAppCrossDomainNavigation: ((String) -> Unit)? = null,
    private val coroutineScope: CoroutineScope,
    private val geckoDownloadManager: GeckoDownloadManager,
    internal val findInPageWebExtension: FindInPageWebExtension,
    internal val devToolsWebExtension: DevToolsWebExtension,
    private val siteSettingsRepository: SiteSettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val webExtensionActionController: WebExtensionActionController,
    private val context: Context,
    private val onRequestDownloadNotificationPermission: suspend () -> Unit = {},
    private val onRequestAndroidPermissions: suspend (Array<String>) -> Array<String> = { emptyArray() },
    var externalDownloadDialogListener: BrowserScreenUiState.ExternalDownloadDialogListener? = null,
    var externalTabInitialUrl: String? = null,
    var onHistoryRecord: (suspend (url: String, title: String) -> Long)? = null,
    var onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)? = null,
) : BrowserSessionStateCallbacks {
    private var currentHistoryEntryId: Long? = null
    private var pendingHistoryTitle: String? = null
    private var historyRecordSequence: Long = 0
    var homepageUrl by mutableStateOf(homepageUrl)
    var searchTemplate by mutableStateOf(searchTemplate)
    var webAppPinnedHost by mutableStateOf(webAppPinnedHost)
    var onWebAppCrossDomainNavigation by mutableStateOf(onWebAppCrossDomainNavigation)
    val session: GeckoSession get() = browserTab.session

    var urlInput by mutableStateOf(browserTab.currentUrl)
    var currentPageUrl by mutableStateOf(browserTab.currentUrl)
    var currentPageTitle by mutableStateOf(browserTab.title)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var isUrlInputFocused by mutableStateOf(false)
    var isPcMode by mutableStateOf(false)

    var toolbarColor: Color?
        get() = browserTab.themeColor?.let { Color(it) }
        set(value) {
            browserTab.themeColor = value?.toArgb()
        }
    private var lastPageStartUrlKey: String = normalizedBrowserPageKey(browserTab.currentUrl)
    private var isFullPageLoadPending: Boolean = false
    private var skipHistoryRecordCount: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    var webAppManifestJson by mutableStateOf<String?>(null)

    var tabHistoryItems by mutableStateOf<List<TabHistoryItem>>(emptyList())
    var tabHistoryCurrentIndex by mutableStateOf(-1)

    data class TabHistoryItem(val uri: String, val title: String)

    var translationState by mutableStateOf(TranslationState.Idle)
    var originalPageUrlForRevert by mutableStateOf<String?>(null)
    var detectedPageLanguage by mutableStateOf<String?>(null)
    var translationFromLanguage by mutableStateOf<String?>(null)
    var translationToLanguage by mutableStateOf<String?>(null)
    private var translationJob: Job? = null

    private var findInPageState by mutableStateOf(FindInPageState.Closed)
    val showFindInPage: Boolean get() = findInPageState != FindInPageState.Closed
    var findQuery by mutableStateOf("")
    var findMatchCurrent by mutableIntStateOf(0)
    var findMatchTotal by mutableIntStateOf(0)
    val findIsRegex: Boolean get() = findInPageState == FindInPageState.Regex
    var findQueryError by mutableStateOf<String?>(null)

    var showDevTools by mutableStateOf(false)
        private set
    var devToolsFocusedInput by mutableStateOf<DevToolsWebExtension.FocusedInputInfo?>(null)
    var showNetworkLog by mutableStateOf(false)
        private set
    var showDevToolsConsole by mutableStateOf(false)
        private set
    var isBackGestureInProgress by mutableStateOf(false)
    var contextMenuState by mutableStateOf<ContextMenuState?>(null)
        private set

    fun dismissContextMenu() {
        contextMenuState = null
    }

    private var hasTouchGestureRecord = false
    private var isTouchGestureActive = false
    private var touchGestureMoved = false
    private var touchGestureStartedAtMs = 0L
    private var touchGestureEndedAtMs = 0L

    fun onContentTouchStart() {
        hasTouchGestureRecord = true
        isTouchGestureActive = true
        touchGestureMoved = false
        touchGestureStartedAtMs = SystemClock.elapsedRealtime()
    }

    fun onContentTouchMoved() {
        touchGestureMoved = true
    }

    fun onContentTouchEnd() {
        isTouchGestureActive = false
        touchGestureEndedAtMs = SystemClock.elapsedRealtime()
    }

    fun onContentNonTouchInput() {
        if (isTouchGestureActive) return
        hasTouchGestureRecord = false
    }

    @Stable
    sealed interface ContextMenuState {
        data class Link(val url: String) : ContextMenuState
        data class Image(val srcUrl: String) : ContextMenuState
        data class LinkWithImage(val url: String, val imageSrcUrl: String) : ContextMenuState
    }

    var addToHomeScreenState by mutableStateOf<AddToHomeScreenState?>(null)
        private set
    private var addToHomeIconJob: Job? = null

    data class AddToHomeScreenState(
        val url: String,
        val title: String,
        val favicon: Bitmap?,
        val isIconLoading: Boolean,
    )

    val promptDialogState = PromptDialogState(coroutineScope)
    val webShareFilesState = WebShareFilesState(coroutineScope)

    var microphonePermissionDialog by mutableStateOf<MicrophonePermissionDialogState?>(null)
        private set

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

    var autoplayPermissionDialog by mutableStateOf<AutoplayPermissionDialogState?>(null)
        private set

    enum class AutoplayPermissionChoice {
        Allow,
        AllowOnce,
        Deny,
    }

    @Stable
    class AutoplayPermissionDialogState(
        val host: String,
        internal val onResult: (AutoplayPermissionChoice?) -> Unit,
    )

    fun confirmAutoplayPermissionDialog(choice: AutoplayPermissionChoice) {
        val dialog = autoplayPermissionDialog ?: return
        autoplayPermissionDialog = null
        dialog.onResult(choice)
    }

    fun dismissAutoplayPermissionDialog() {
        val dialog = autoplayPermissionDialog ?: return
        autoplayPermissionDialog = null
        dialog.onResult(null)
    }

    private suspend fun resolveAutoplayPermission(host: String): Boolean {
        siteSettingsRepository.markAutoplayPermissionRequested(host)
        when (siteSettingsRepository.getAutoplayPermission(host)) {
            SitePermissionState.SITE_PERMISSION_ALLOW -> return true
            SitePermissionState.SITE_PERMISSION_DENY -> return false
            else -> Unit
        }
        autoplayPermissionDialog?.also { previous ->
            autoplayPermissionDialog = null
            previous.onResult(null)
        }
        val result = CompletableDeferred<AutoplayPermissionChoice?>()
        autoplayPermissionDialog = AutoplayPermissionDialogState(host) { choice -> result.complete(choice) }
        val persistedState = when (val choice = result.await()) {
            AutoplayPermissionChoice.Allow -> SitePermissionState.SITE_PERMISSION_ALLOW
            AutoplayPermissionChoice.Deny -> SitePermissionState.SITE_PERMISSION_DENY
            AutoplayPermissionChoice.AllowOnce, null -> return choice == AutoplayPermissionChoice.AllowOnce
        }
        siteSettingsRepository.setAutoplayPermission(host = host, state = persistedState)
        return persistedState == SitePermissionState.SITE_PERMISSION_ALLOW
    }

    private suspend fun resolveMicrophonePermission(host: String): Boolean {
        siteSettingsRepository.markMicrophonePermissionRequested(host)
        when (siteSettingsRepository.getMicrophonePermission(host)) {
            SitePermissionState.SITE_PERMISSION_ALLOW -> return true
            SitePermissionState.SITE_PERMISSION_DENY -> return false
            else -> Unit
        }
        microphonePermissionDialog?.also { previous ->
            microphonePermissionDialog = null
            previous.onResult(null)
        }
        val result = CompletableDeferred<Boolean?>()
        microphonePermissionDialog = MicrophonePermissionDialogState(host) { allow -> result.complete(allow) }
        val choice = result.await()
        if (choice != null) {
            siteSettingsRepository.setMicrophonePermission(
                host = host,
                state = if (choice) SitePermissionState.SITE_PERMISSION_ALLOW else SitePermissionState.SITE_PERMISSION_DENY,
            )
        }
        return choice == true
    }

    var pendingDownloadResponse by mutableStateOf<WebResponse?>(null)
    var pendingExternalAppLaunch by mutableStateOf<PendingExternalAppLaunch?>(null)
    private var queuedExternalAppLaunch: PendingExternalAppLaunch? = null
    var duplicateDownloadState by mutableStateOf<DuplicateDownloadState?>(null)
        private set

    @Stable
    class DuplicateDownloadState(
        val url: String,
        val existingDownloads: List<DuplicateDownloadEntry>,
        internal val onConfirm: () -> Unit,
        internal val onDismiss: () -> Unit = {},
        internal val onCancel: () -> Unit = {},
    )

    data class DuplicateDownloadEntry(
        val fileName: String,
        val status: DownloadRecordStatus,
        val fileUri: String?,
    )

    private var skipExternalAppCheckForNextLoad = false
    var isFullScreen by mutableStateOf(false)
    var renderReady by mutableStateOf(false)
    var sessionRecoveryRequestCount by mutableIntStateOf(0)
        private set
    var capturePreviewRequestCount by mutableIntStateOf(0)
        private set

    private var previewCaptureReady: Boolean =
        browserTab.previewBitmap?.isNotEmpty() == true || browserTab.sessionState.isNotBlank()
        set(value) {
            if (field == value) return
            Log.d(TAG, "previewCaptureReady $field -> $value (tabId=${browserTab.tabId} url=$currentPageUrl)")
            field = value
        }

    var pageLoadError by mutableStateOf<PageLoadError?>(null)
    val pageZoomPercent: Int
        get() = browserTab.pageZoomPercent
    var visualViewportScale by mutableFloatStateOf(1f)
    var isRefreshing by mutableStateOf(false)
    var isPageLoading by mutableStateOf(browserTab.isPageLoading)
    var scrollY: Int
        get() = browserTab.scrollY
        set(value) {
            browserTab.scrollY = value
        }
    val showInstallExtensionItem: Boolean
        get() = resolveAmoInstallUriFromPage(currentPageUrl) != null
    val extensionActionScrollState = ScrollState(initial = 0)
    var extensionActionPopup by mutableStateOf<WebExtensionActionController.PopupRequest?>(null)
    private var extensionActionOrder by mutableStateOf<List<String>>(emptyList())
    private var draggingExtensionActionOrder by mutableStateOf<List<String>?>(null)

    init {
        Log.d(
            TAG,
            "init previewCaptureReady=$previewCaptureReady (tabId=${browserTab.tabId} hasPreview=${browserTab.previewBitmap?.isNotEmpty() == true} hasSessionState=${browserTab.sessionState.isNotBlank()})",
        )
        coroutineScope.launch {
            settingsRepository.settings.collect { settings ->
                extensionActionOrder = settings.extensionActionOrderList
            }
        }
    }

    val extensionActions: List<WebExtensionActionController.ActionUiState>
        get() = sortByExtensionActionOrder(
            items = webExtensionActionController.actions(
                session,
                object : WebExtensionActionController.ActionClickHandler {
                    override fun onActionClick(extensionId: String) {
                        onExtensionActionClick(extensionId)
                    }
                },
            ),
            order = draggingExtensionActionOrder ?: extensionActionOrder,
            idOf = { it.extensionId },
        )

    fun onUrlSubmit(rawInput: String) {
        val resolved = buildUrlFromInput(rawInput, homepageUrl, searchTemplate)
        if (handleWebAppCrossDomainNavigation(resolved)) return
        urlInput = resolved
        maybeResetToolbarColor(currentPageUrl, resolved)
        currentPageUrl = resolved
        clearPageLoadError()
        browserTab.cancelPendingInitialLoad()
        session.loadUri(resolved)
    }

    fun openUrlWithReferrer(url: String) {
        if (handleWebAppCrossDomainNavigation(url)) return
        val referrerUrl = currentPageUrl
        urlInput = url
        maybeResetToolbarColor(currentPageUrl, url)
        currentPageUrl = url
        clearPageLoadError()
        browserTab.cancelPendingInitialLoad()
        session.load(GeckoSession.Loader().uri(url).referrer(referrerUrl))
    }

    fun onHome() {
        urlInput = homepageUrl
        maybeResetToolbarColor(currentPageUrl, homepageUrl)
        currentPageUrl = homepageUrl
        clearPageLoadError()
        browserTab.cancelPendingInitialLoad()
        session.loadUri(homepageUrl)
    }

    fun onRefresh() { refreshCurrentPage() }
    fun onSuperRefresh() { superRefreshCurrentPage() }
    fun onStopLoading() {
        session.stop()
        isRefreshing = false
        browserTab.clearPageLoadingState()
        isPageLoading = false
    }
    fun onRefreshFromSwipe() {
        refreshCurrentPage()
        isRefreshing = false
    }

    fun onGoForward() {
        clearPageLoadError()
        skipHistoryRecordCount++
        if (tabHistoryCurrentIndex < tabHistoryItems.lastIndex) tabHistoryCurrentIndex++
        session.goForward()
    }

    fun onGoBack() {
        clearPageLoadError()
        skipHistoryRecordCount++
        if (tabHistoryCurrentIndex > 0) tabHistoryCurrentIndex--
        session.goBack()
    }

    fun jumpToHistoryEntry(targetIndex: Int) {
        if (targetIndex == tabHistoryCurrentIndex) return
        skipHistoryRecordCount++
        tabHistoryCurrentIndex = targetIndex
        session.gotoHistoryIndex(targetIndex)
    }

    fun togglePcMode() {
        val newMode = !isPcMode
        isPcMode = newMode
        session.settings.userAgentMode = if (newMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
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

    fun resetPageZoom() { applyPageZoom(100) }
    fun onExtensionActionClick(extensionId: String) { webExtensionActionController.click(session, extensionId) }
    fun onExtensionActionMove(fromIndex: Int, toIndex: Int) {
        val currentIds = extensionActions.map { it.extensionId }
        draggingExtensionActionOrder = moveExtensionActionOrder(currentIds, fromIndex, toIndex) ?: return
    }
    fun onExtensionActionMoveCancel() { draggingExtensionActionOrder = null }
    fun onExtensionActionMoveEnd() {
        val visibleOrder = draggingExtensionActionOrder ?: return
        draggingExtensionActionOrder = null
        val merged = mergeVisibleExtensionActionOrder(extensionActionOrder, visibleOrder)
        extensionActionOrder = merged
        coroutineScope.launch { settingsRepository.setExtensionActionOrder(merged) }
    }
    fun dismissExtensionActionPopup() {
        webExtensionActionController.closePopup(session)
        extensionActionPopup = null
    }

    private fun applyPageZoom(percent: Int) {
        browserTab.pageZoomPercent = percent
        injectViewportZoom(percent)
    }

    private fun maybeApplyPersistedPageZoomAfterRender() {
        if (!shouldApplyPersistedPageZoomAfterRender(pageZoomPercent, isFullPageLoadPending)) return
        injectViewportZoom(pageZoomPercent)
    }

    private fun injectViewportZoom(percent: Int) {
        val screenWidthDp = (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
        val viewportContent = viewportContentForPageZoom(screenWidthDp, percent)
        val script = buildViewportZoomInjectionScript(viewportContent, percent != 100)
        session.loadUri(script)
    }

    fun openFindInPage() { findInPageState = FindInPageState.Normal }
    fun closeFindInPage() {
        val previous = findInPageState
        findInPageState = FindInPageState.Closed
        if (previous == FindInPageState.Regex) findInPageWebExtension.clear(session) else session.finder.clear()
        findQuery = ""
        findMatchCurrent = 0
        findMatchTotal = 0
        findQueryError = null
    }

    fun onFindQueryChange(newQuery: String) {
        findQuery = newQuery
        findQueryError = null
        if (newQuery.isEmpty()) {
            if (findIsRegex) findInPageWebExtension.clear(session) else session.finder.clear()
            findMatchCurrent = 0
            findMatchTotal = 0
        } else if (findIsRegex) {
            findInPageWebExtension.search(session, newQuery, isRegex = true)
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
            if (findIsRegex) findInPageWebExtension.findNext(session) else session.finder.find(findQuery, 0).then<Void?> { result ->
                findMatchCurrent = result?.current ?: 0
                findMatchTotal = result?.total ?: 0
                null
            }
        }
    }

    fun findPrevious() {
        if (findQuery.isNotEmpty()) {
            if (findIsRegex) findInPageWebExtension.findPrevious(session) else session.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS).then<Void?> { result ->
                findMatchCurrent = result?.current ?: 0
                findMatchTotal = result?.total ?: 0
                null
            }
        }
    }

    fun toggleFindRegex() {
        val newState = if (findInPageState == FindInPageState.Regex) FindInPageState.Normal else FindInPageState.Regex
        findInPageState = newState
        findQueryError = null
        if (findQuery.isNotEmpty()) {
            if (newState == FindInPageState.Regex) {
                session.finder.clear()
                findInPageWebExtension.search(session, findQuery, isRegex = true)
            } else {
                findInPageWebExtension.clear(session)
                session.finder.find(findQuery, 0).then<Void?> { result ->
                    findMatchCurrent = result?.current ?: 0
                    findMatchTotal = result?.total ?: 0
                    null
                }
            }
        }
    }

    fun openDevTools() {
        showDevTools = true
        devToolsWebExtension.requestFocusedInput(session)
    }

    fun copyFocusedInputId() {
        val id = devToolsFocusedInput?.id?.takeIf { it.isNotBlank() } ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("input id", id))
        Toast.makeText(context, "id をコピーしました", Toast.LENGTH_SHORT).show()
    }

    fun closeDevTools() { showDevTools = false }
    fun openNetworkLog() { showDevTools = false; showNetworkLog = true }
    fun closeNetworkLog() { showNetworkLog = false }
    fun openDevToolsConsole() { showDevTools = false; showDevToolsConsole = true }
    fun closeDevToolsConsole() { showDevToolsConsole = false }

    fun onTranslate(translationProvider: TranslationProvider) {
        when (translationState) {
            TranslationState.Idle -> runTranslation(translationProvider, detectedPageLanguage, TranslationPriorityLanguage.TO)
            TranslationState.Loading, TranslationState.Translated -> closeTranslationBar(revertPage = true)
            TranslationState.Error -> closeTranslationBar(revertPage = false)
        }
    }

    fun onRetranslate(translationProvider: TranslationProvider, fromLanguage: String?, toLanguage: String) {
        if (translationState == TranslationState.Loading) return
        runTranslation(translationProvider, fromLanguage, toLanguage)
    }

    private fun runTranslation(translationProvider: TranslationProvider, fromLanguage: String?, toLanguage: String) {
        translationJob?.cancel()
        translationJob = coroutineScope.launch {
            if (originalPageUrlForRevert == null) originalPageUrlForRevert = currentPageUrl
            val startUrl = originalPageUrlForRevert
            translationState = TranslationState.Loading
            val result = runCatching { PageTranslator(session, startUrl ?: currentPageUrl).translatePage(translationProvider, fromLanguage, toLanguage) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (originalPageUrlForRevert != startUrl) return@launch
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
        closeTranslationBar(false)
        if (savedUrl != null) { clearPageLoadError(); session.loadUri(savedUrl) }
    }
    fun onDismissTranslationError() { closeTranslationBar(false) }
    private fun closeTranslationBar(revertPage: Boolean) {
        translationJob?.cancel()
        translationJob = null
        val savedUrl = originalPageUrlForRevert
        translationState = TranslationState.Idle
        originalPageUrlForRevert = null
        translationFromLanguage = null
        translationToLanguage = null
        if (revertPage && savedUrl != null) { clearPageLoadError(); session.loadUri(savedUrl) }
    }

    fun sharePage() { shareText("$currentPageTitle\n$currentPageUrl") }
    fun shareText(text: String) { launchPlainTextShare(text) }
    private fun launchPlainTextShare(body: String, subject: String? = null) {
        try { context.startActivity(Intent.createChooser(buildPlainTextShareIntent(body, subject), null)) } catch (_: ActivityNotFoundException) { }
    }

    fun downloadImage(imageUrl: String) {
        dismissContextMenu()
        val referrerUrl = currentPageUrl
        coroutineScope.launch {
            val duplicates = geckoDownloadManager.findDuplicateDownloads(imageUrl)
            if (duplicates.isNotEmpty()) {
                duplicateDownloadState = DuplicateDownloadState(
                    url = imageUrl,
                    existingDownloads = duplicates.map { DuplicateDownloadEntry(it.fileName, it.status, it.fileUri) },
                    onConfirm = { proceedDownloadImage(imageUrl, referrerUrl) },
                )
                return@launch
            }
            proceedDownloadImage(imageUrl, referrerUrl)
        }
    }

    private fun proceedDownloadImage(imageUrl: String, referrerUrl: String) {
        coroutineScope.launch {
            onRequestDownloadNotificationPermission()
            geckoDownloadManager.enqueueDownload(imageUrl, referrerUrl, coroutineScope)
        }
    }

    fun downloadFileFromResponse(response: WebResponse) {
        val referrerUrl = currentPageUrl
        coroutineScope.launch {
            val duplicates = geckoDownloadManager.findDuplicateDownloads(response.uri)
            if (duplicates.isNotEmpty()) {
                duplicateDownloadState = DuplicateDownloadState(
                    url = response.uri,
                    existingDownloads = duplicates.map { DuplicateDownloadEntry(it.fileName, it.status, it.fileUri) },
                    onConfirm = { proceedDownloadFromResponse(response, referrerUrl) { finishExternalDownloadTabIfNeeded(response.uri) } },
                    onDismiss = { response.body?.close() },
                    onCancel = { finishExternalDownloadTabIfNeeded(response.uri) },
                )
                return@launch
            }
            pendingDownloadResponse = response
        }
    }

    fun confirmPendingDownload() {
        val response = pendingDownloadResponse ?: return
        pendingDownloadResponse = null
        proceedDownloadFromResponse(response, currentPageUrl) { finishExternalDownloadTabIfNeeded(response.uri) }
    }
    fun cancelPendingDownload() {
        val response = pendingDownloadResponse
        pendingDownloadResponse?.body?.close()
        pendingDownloadResponse = null
        response?.let { finishExternalDownloadTabIfNeeded(it.uri) }
    }
    fun dismissPendingDownload() { pendingDownloadResponse?.body?.close(); pendingDownloadResponse = null }

    private fun finishExternalDownloadTabIfNeeded(responseUri: String) {
        val initialUrl = externalTabInitialUrl ?: return
        if (!matchesExternalDownloadInitialUrl(initialUrl, responseUri)) return
        externalDownloadDialogListener?.onResolved()
    }

    private fun proceedDownloadFromResponse(response: WebResponse, referrerUrl: String, onEnqueued: (() -> Unit)? = null) {
        coroutineScope.launch {
            proceedDownloadFromResponse(
                awaitPermission = { onRequestDownloadNotificationPermission() },
                enqueue = { geckoDownloadManager.enqueueDownloadFromResponse(response, referrerUrl) },
                onEnqueued = onEnqueued,
                onEnqueueFailed = { response.body?.close() },
            )
        }
    }

    fun confirmDuplicateDownload() {
        val state = duplicateDownloadState ?: return
        duplicateDownloadState = null
        state.onConfirm()
    }
    fun cancelDuplicateDownload() {
        val state = duplicateDownloadState ?: return
        duplicateDownloadState = null
        state.onDismiss(); state.onCancel()
    }
    fun dismissDuplicateDownload() {
        val state = duplicateDownloadState ?: return
        duplicateDownloadState = null
        state.onDismiss()
    }

    fun confirmPendingExternalAppLaunch() {
        val request = pendingExternalAppLaunch ?: return
        pendingExternalAppLaunch = null
        val result = launchExternalApp(context, request)
        if (result.isSuccess) { queuedExternalAppLaunch = null; return }
        val fallbackUrl = request.fallbackUrl
        if (fallbackUrl != null) { queuedExternalAppLaunch = null; openFallbackUrl(fallbackUrl); return }
        promoteQueuedExternalAppLaunch()
        if (pendingExternalAppLaunch != null) return
        Toast.makeText(context, "対応するアプリを開けませんでした", Toast.LENGTH_SHORT).show()
    }

    fun dismissPendingExternalAppLaunch() { pendingExternalAppLaunch = null; promoteQueuedExternalAppLaunch() }
    fun dismissPendingExternalAppLaunchAndLoadInBrowser() {
        val request = pendingExternalAppLaunch ?: return
        pendingExternalAppLaunch = null
        val queued = queuedExternalAppLaunch
        queuedExternalAppLaunch = null
        if (queued != null) { pendingExternalAppLaunch = queued; return }
        val url = if (request.sourceUri.startsWith("http://") || request.sourceUri.startsWith("https://")) request.sourceUri else request.fallbackUrl
        if (url != null) { skipExternalAppCheckForNextLoad = true; openFallbackUrl(url) }
    }
    private fun promoteQueuedExternalAppLaunch() {
        val queued = queuedExternalAppLaunch ?: return
        queuedExternalAppLaunch = null
        pendingExternalAppLaunch = queued
    }

    fun restoreCurrentPageUrlToInput() { urlInput = currentPageUrl }
    fun retryPageLoad() { refreshCurrentPage() }

    fun requestAddToHomeScreen() {
        val pageUrl = currentPageUrl
        val pageTitle = currentPageTitle
        val manifestJson = webAppManifestJson
        val fallbackFavicon = browserTab.faviconBitmap
        addToHomeIconJob?.cancel()
        addToHomeScreenState = AddToHomeScreenState(pageUrl, pageTitle, null, true)
        addToHomeIconJob = coroutineScope.launch {
            val fetchedIcon = try { HomeScreenIconFetcher.fetchIcon(pageUrl, manifestJson) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            try {
                val current = addToHomeScreenState ?: return@launch
                if (current.url != pageUrl) return@launch
                addToHomeScreenState = current.copy(favicon = fetchedIcon ?: fallbackFavicon, isIconLoading = false)
                if (fetchedIcon != null && currentPageUrl == pageUrl) browserTab.faviconBitmap = fetchedIcon
            } finally {
                val current = addToHomeScreenState
                if (current != null && current.url == pageUrl && current.isIconLoading) {
                    addToHomeScreenState = current.copy(favicon = current.favicon ?: fallbackFavicon, isIconLoading = false)
                }
            }
        }
    }

    fun dismissAddToHomeScreen() {
        addToHomeIconJob?.cancel(); addToHomeIconJob = null; addToHomeScreenState = null
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
            onCaptured?.invoke(); return
        }
        Log.d(TAG, "captureTabPreview start (tabId=${browserTab.tabId} url=$currentPageUrl)")
        geckoView.capturePixels().accept(
            { bitmap ->
                val previewBitmap = bitmap ?: run { Log.w(TAG, "capturePixels returned null bitmap (tabId=${browserTab.tabId})"); onCaptured?.invoke(); return@accept }
                onCaptured?.invoke()
                val tabIdForLog = browserTab.tabId
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    if (previewBitmap.isRecycled) { Log.w(TAG, "previewBitmap recycled before compress (tabId=$tabIdForLog)"); return@launch }
                    val copiedBitmap: Bitmap? = if (previewBitmap.config == Bitmap.Config.HARDWARE) {
                        runCatching { previewBitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrElse { Log.e(TAG, "HARDWARE copy threw (tabId=$tabIdForLog)", it); return@launch } ?: run { Log.e(TAG, "HARDWARE copy returned null (tabId=$tabIdForLog)"); return@launch }
                    } else null
                    val sourceBitmap = copiedBitmap ?: previewBitmap
                    val stream = ByteArrayOutputStream()
                    val success = runCatching { sourceBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, stream) }.getOrElse { Log.e(TAG, "compress threw (tabId=$tabIdForLog)", it); false }
                    copiedBitmap?.recycle()
                    val bytes = stream.toByteArray()
                    if (!success || bytes.isEmpty()) { Log.w(TAG, "compress failed success=$success size=${bytes.size} (tabId=$tabIdForLog)"); return@launch }
                    browserTab.previewBitmap = bytes
                    Log.d(TAG, "previewBitmap saved size=${bytes.size} (tabId=$tabIdForLog)")
                }
            },
            { error -> Log.w(TAG, "capturePixels error (tabId=${browserTab.tabId})", error); onCaptured?.invoke() },
        )
    }

    fun flushAndCaptureForTabSwitch(geckoViewRef: GeckoView) { session.flushSessionState(); captureTabPreview(geckoViewRef) }

    override fun onCanGoBackChanged(value: Boolean) { canGoBack = value; browserTab.canGoBack = value }
    override fun onCanGoForwardChanged(value: Boolean) { canGoForward = value }
    override fun onHistoryStateChange(items: List<HistoryStateItem>, currentIndex: Int) {
        tabHistoryItems = items.map { TabHistoryItem(it.uri, it.title) }; tabHistoryCurrentIndex = currentIndex
    }

    override fun onLoadError(uri: String?, error: WebRequestError) {
        val resolvedError = error.toPageLoadError(uri)
        val failedUrl = resolvedError.failingUrl
        if (failedUrl.isNotBlank()) {
            maybeResetToolbarColor(currentPageUrl, failedUrl)
            currentPageUrl = failedUrl
            if (!isUrlInputFocused) urlInput = failedUrl
        }
        currentPageTitle = resolvedError.title
        pageLoadError = resolvedError
    }

    override fun onLocationChange(url: String) {
        if (url == "about:blank" && currentPageUrl != "about:blank") return
        if (url.startsWith("javascript:")) return
        if (pageLoadError?.failingUrl != url) clearPageLoadError()
        val wasFullPageLoad = isFullPageLoadPending
        if (isFullPageLoadPending) {
            maybeResetToolbarColorOnPageStart(url); isFullPageLoadPending = false
        } else {
            markRenderingDone()
            if (shouldReapplyPageZoomOnSpaLocationChange(pageZoomPercent, wasFullPageLoad)) {
                val zoomToApply = pageZoomPercent
                mainHandler.post { injectViewportZoom(zoomToApply) }
            }
        }
        currentPageUrl = url
        if (!isUrlInputFocused) urlInput = url
        if (shouldResetTranslationOnLocationChange(translationState, url, originalPageUrlForRevert, wasFullPageLoad)) {
            translationState = TranslationState.Idle; originalPageUrlForRevert = null
        }
        if (!url.startsWith("data:")) detectedPageLanguage = null
        val shouldRecord = url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("data:")
        val skip = skipHistoryRecordCount > 0
        if (skip) skipHistoryRecordCount--
        if (shouldRecord && !skip) {
            val newItem = TabHistoryItem(url, "")
            tabHistoryItems = tabHistoryItems.take(tabHistoryCurrentIndex + 1) + newItem
            tabHistoryCurrentIndex = tabHistoryItems.lastIndex
            historyRecordSequence++
            val sequence = historyRecordSequence
            currentHistoryEntryId = null; pendingHistoryTitle = null
            val callback = onHistoryRecord
            if (callback != null) coroutineScope.launch {
                val historyEntryId = callback(url, "")
                if (historyRecordSequence != sequence) return@launch
                currentHistoryEntryId = historyEntryId
                applyPendingHistoryTitle(historyEntryId)
            }
        }
    }

    override fun onTitleChange(title: String) {
        currentPageTitle = title
        val entryId = currentHistoryEntryId
        val callback = onHistoryTitleUpdate
        if (tabHistoryCurrentIndex in tabHistoryItems.indices) {
            tabHistoryItems = tabHistoryItems.toMutableList().apply { this[tabHistoryCurrentIndex] = this[tabHistoryCurrentIndex].copy(title = title) }
        }
        if (entryId != null && title.isNotBlank() && callback != null) coroutineScope.launch { callback(entryId, title) } else if (title.isNotBlank()) pendingHistoryTitle = title
    }

    override fun onWebAppManifest(manifest: JSONObject) { webAppManifestJson = manifest.toString() }

    override fun onContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
        if (isBackGestureInProgress) return
        if (!shouldShowContextMenuForGesture(hasTouchGestureRecord, isTouchGestureActive, touchGestureMoved, SystemClock.elapsedRealtime() - touchGestureStartedAtMs, SystemClock.elapsedRealtime() - touchGestureEndedAtMs)) return
        val linkUri = element.linkUri
        val srcUri = element.srcUri
        val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
        contextMenuState = when {
            linkUri != null && isImage && srcUri != null -> ContextMenuState.LinkWithImage(linkUri, srcUri)
            linkUri != null -> ContextMenuState.Link(linkUri)
            isImage && srcUri != null -> ContextMenuState.Image(srcUri)
            else -> null
        }
    }

    override fun onRenderReady() { renderReady = true; maybeApplyPersistedPageZoomAfterRender() }
    override fun onPreviewCaptureReady() { renderReady = true; previewCaptureReady = true; maybeApplyPersistedPageZoomAfterRender(); capturePreviewRequestCount++ }
    override fun onExternalResponse(response: WebResponse) { downloadFileFromResponse(response) }
    override fun onSessionStateChange(sessionState: GeckoSession.SessionState) { }
    override fun onPageLoadingChanged(value: Boolean) { browserTab.isPageLoading = value; isPageLoading = value }
    override fun onPageStart(url: String) {
        clearPageLoadError(); visualViewportScale = 1f; browserTab.faviconBitmap = null; webAppManifestJson = null; isFullPageLoadPending = true
    }
    override fun onPageStop(success: Boolean) {
        isFullPageLoadPending = false; renderReady = true; previewCaptureReady = true; capturePreviewRequestCount++
        if (success) { fetchFavicon(currentPageUrl); if (pageZoomPercent != 100) injectViewportZoom(pageZoomPercent) }
    }

    private fun fetchFavicon(pageUrl: String) {
        val uri = runCatching { java.net.URI(pageUrl) }.getOrNull() ?: return
        val scheme = uri.scheme ?: return
        if (scheme != "http" && scheme != "https") return
        val host = uri.host ?: return
        val faviconUrl = "$scheme://$host/favicon.ico"
        coroutineScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val connection = URL(faviconUrl).openConnection() as java.net.HttpURLConnection
                try { connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.connect(); connection.inputStream.use { BitmapFactory.decodeStream(it) } } finally { connection.disconnect() }
            }.getOrNull()
            if (bitmap != null && currentPageUrl == pageUrl) browserTab.faviconBitmap = bitmap
        }
    }

    internal fun handleWebAppCrossDomainNavigation(url: String): Boolean {
        if (!isWebAppCrossDomainNavigation(url, webAppPinnedHost)) return false
        onWebAppCrossDomainNavigation?.invoke(url)
        return onWebAppCrossDomainNavigation != null
    }

    override fun onLoadRequest(request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
        if (skipExternalAppCheckForNextLoad) { skipExternalAppCheckForNextLoad = false; return null }
        if (pendingExternalAppLaunch != null) {
            val externalAction = resolveExternalAppNavigationAction(context, request.uri)
            if (externalAction is ExternalAppNavigationAction.Launch) queuedExternalAppLaunch = externalAction.request
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }
        val externalAction = resolveExternalAppNavigationAction(context, request.uri)
        if (isSinglePageMode && request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
            return when (externalAction) {
                ExternalAppNavigationAction.AllowInBrowser -> null
                ExternalAppNavigationAction.AppNotFound -> { Toast.makeText(context, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show(); GeckoResult.fromValue(AllowOrDeny.DENY) }
                is ExternalAppNavigationAction.Launch -> { pendingExternalAppLaunch = externalAction.request; GeckoResult.fromValue(AllowOrDeny.DENY) }
                is ExternalAppNavigationAction.OpenFallback -> { openFallbackUrl(externalAction.url); GeckoResult.fromValue(AllowOrDeny.DENY) }
            }
        }
        return when (externalAction) {
            ExternalAppNavigationAction.AllowInBrowser -> if (handleWebAppCrossDomainNavigation(request.uri)) GeckoResult.fromValue(AllowOrDeny.DENY) else null
            ExternalAppNavigationAction.AppNotFound -> { Toast.makeText(context, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show(); GeckoResult.fromValue(AllowOrDeny.DENY) }
            is ExternalAppNavigationAction.Launch -> { pendingExternalAppLaunch = externalAction.request; GeckoResult.fromValue(AllowOrDeny.DENY) }
            is ExternalAppNavigationAction.OpenFallback -> { openFallbackUrl(externalAction.url); GeckoResult.fromValue(AllowOrDeny.DENY) }
        }
    }

    override fun onTranslationStateChange(translationState: TranslationsController.SessionTranslation.TranslationState?) {
        val lang = translationState?.detectedLanguages?.docLangTag ?: return
        detectedPageLanguage = lang
    }
    override fun onFullScreen(fullScreen: Boolean) { isFullScreen = fullScreen }
    fun exitFullScreen() { session.exitFullScreen() }
    override fun onScrollChanged(scrollY: Int) { this.scrollY = scrollY }
    override fun onSessionClosedUnexpectedly() { Log.w(TAG, "onSessionClosedUnexpectedly: コンテンツプロセスが失われました。復元をリクエストします"); renderReady = false; sessionRecoveryRequestCount++ }

    override fun onAndroidPermissionsRequest(permissions: Array<String>?, onGrant: () -> Unit, onReject: () -> Unit) {
        val perms = permissions ?: run { onReject(); return }
        coroutineScope.launch {
            if (Manifest.permission.RECORD_AUDIO in perms) {
                val host = extractSiteHost(currentPageUrl)
                if (host == null || !resolveMicrophonePermission(host)) { onReject(); return@launch }
            }
            runCatching { onRequestAndroidPermissions(perms) }.onSuccess { granted -> if (perms.all { it in granted }) onGrant() else onReject() }.onFailure { onReject() }
        }
    }

    override fun onMediaPermissionRequest(uri: String, hasVideo: Boolean, hasAudio: Boolean, onResult: (Boolean, Boolean) -> Unit) {
        if (!hasAudio) { onResult(hasVideo, false); return }
        coroutineScope.launch {
            val host = extractSiteHost(uri) ?: extractSiteHost(currentPageUrl)
            onResult(hasVideo, host != null && resolveMicrophonePermission(host))
        }
    }

    override fun onGeolocationPermissionRequest(uri: String?, onResult: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        val job = coroutineScope.launch {
            val host = extractSiteHost(currentPageUrl) ?: uri?.let { extractSiteHost(it) }
            val allow = host != null && runCatching { siteSettingsRepository.getGeolocationState(host) }.getOrNull() == SiteGeolocationState.SITE_GEOLOCATION_REAL
            if (completed.compareAndSet(false, true)) onResult(allow)
        }
        job.invokeOnCompletion { cause -> if (cause != null && completed.compareAndSet(false, true)) onResult(false) }
    }

    override fun onAutoplayPermissionRequest(uri: String?, onResult: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        val job = coroutineScope.launch {
            val host = extractSiteHost(currentPageUrl) ?: uri?.let { extractSiteHost(it) }
            val allow = host != null && resolveAutoplayPermission(host)
            if (completed.compareAndSet(false, true)) onResult(allow)
        }
        job.invokeOnCompletion { cause -> if (cause != null && completed.compareAndSet(false, true)) onResult(false) }
    }

    private fun maybeResetToolbarColor(fromUrl: String, toUrl: String) {
        if (net.matsudamper.browser.shouldResetToolbarColor(fromUrl, toUrl)) toolbarColor = null
    }

    private fun refreshCurrentPage() {
        val retryUrl = pageLoadError?.failingUrl?.takeIf { it.isNotBlank() }
        clearPageLoadError()
        if (retryUrl != null) {
            currentPageUrl = retryUrl
            if (!isUrlInputFocused) urlInput = retryUrl
            session.loadUri(retryUrl); return
        }
        session.reload()
    }

    private fun superRefreshCurrentPage() {
        val retryUrl = pageLoadError?.failingUrl?.takeIf { it.isNotBlank() }
        clearPageLoadError()
        if (retryUrl != null) {
            currentPageUrl = retryUrl
            if (!isUrlInputFocused) urlInput = retryUrl
            session.load(GeckoSession.Loader().uri(retryUrl).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)); return
        }
        session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    private fun clearPageLoadError() { pageLoadError = null }
    private fun applyPendingHistoryTitle(entryId: Long) {
        val title = pendingHistoryTitle
        val callback = onHistoryTitleUpdate
        if (title.isNullOrBlank() || callback == null) return
        pendingHistoryTitle = null
        coroutineScope.launch { callback(entryId, title) }
    }
    private fun maybeResetToolbarColorOnPageStart(url: String) {
        val nextKey = normalizedBrowserPageKey(url)
        if (nextKey == lastPageStartUrlKey) return
        toolbarColor = null; lastPageStartUrlKey = nextKey
    }
    private fun openFallbackUrl(url: String) {
        maybeResetToolbarColor(currentPageUrl, url); currentPageUrl = url
        if (!isUrlInputFocused) urlInput = url
        clearPageLoadError(); session.loadUri(url)
    }
    private fun markRenderingDone() { renderReady = true; previewCaptureReady = true }

    private fun copyUrlToClipboard(url: String) {
        copyUrlToClipboard(context, url)
    }
}

internal fun isWebAppCrossDomainNavigation(url: String, pinnedHost: String?): Boolean {
    if (pinnedHost.isNullOrBlank()) return false
    val targetHost = extractSiteHost(url) ?: return false
    return !targetHost.equals(pinnedHost, ignoreCase = true)
}

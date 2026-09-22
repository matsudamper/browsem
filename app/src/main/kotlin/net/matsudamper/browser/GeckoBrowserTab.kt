package net.matsudamper.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.feature.addressautofill.AddressAutofillCoordinator
import net.matsudamper.browser.feature.addressautofill.AddressAutofillDelegate
import net.matsudamper.browser.feature.findinpage.FindInPageWebExtension
import net.matsudamper.browser.feature.forminputautofill.FormInputAutofillCoordinator
import net.matsudamper.browser.feature.media.GeckoMediaSessionDelegate
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.feature.mocklocation.MockLocationWebExtension
import net.matsudamper.browser.feature.networklog.NetworkLogWebExtension
import net.matsudamper.browser.feature.themecolor.ThemeColorWebExtension
import net.matsudamper.browser.feature.twittershare.TwitterShareWebExtension
import net.matsudamper.browser.feature.viewportscale.ViewportScaleWebExtension
import net.matsudamper.browser.feature.websharefiles.WebShareFilesWebExtension
import net.matsudamper.browser.translate.PageTranslationWebExtension
import net.matsudamper.browser.translate.TranslationPriorityLanguage
import net.matsudamper.browser.ui.browser.BrowserScreenUiState
import net.matsudamper.browser.ui.browser.UrlBarSuggestionsUiState
import net.matsudamper.browser.ui.common.StatusBarAppearanceEffect
import net.matsudamper.browser.ui.common.findActivity
import net.matsudamper.browser.ui.common.resolveBrowserToolbarColors
import org.json.JSONObject
import org.koin.compose.koinInject
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.SelectionActionDelegate
import org.mozilla.geckoview.GeckoView

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GeckoBrowserTab(
    browserTab: BrowserTab,
    homepageUrl: String,
    searchTemplate: String,
    translationProvider: TranslationProvider,
    geminiNanoModelKey: String,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
    tabCount: Int?,
    onInstallExtensionRequest: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSiteSettings: ((currentUrl: String) -> Unit)?,
    onOpenDownloads: (() -> Unit)?,
    onOpenTabs: () -> Unit,
    onOpenNewSessionRequest: (String) -> GeckoSession?,
    onOpenNewTabRequest: (url: String, referrerUrl: String?) -> Unit,
    onReevaluateOpenerRetention: () -> Unit,
    onRequestDownloadNotificationPermission: suspend () -> Unit,
    enableTabUi: Boolean,
    showInstallExtensionItem: Boolean,
    customTabMode: Boolean,
    webAppMode: Boolean,
    webAppPinnedHost: String?,
    onWebAppCrossDomainNavigation: ((String) -> Unit)?,
    onCloseCustomTab: (() -> Unit)?,
    onOpenInBrowser: ((String) -> Unit)?,
    onCloseTab: (() -> Unit)?,
    externalDownloadDialogListener: BrowserScreenUiState.ExternalDownloadDialogListener?,
    externalTabInitialUrl: String?,
    onToolbarHorizontalDrag: (Float) -> Unit,
    onToolbarDragEnd: () -> Unit,
    onHistoryRecord: (suspend (url: String, title: String) -> Long)?,
    onHistoryTitleUpdate: (suspend (id: Long, title: String) -> Unit)?,
    urlBarSuggestions: UrlBarSuggestionsUiState,
    onUrlInputChanged: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val findInPageWebExtension: FindInPageWebExtension = koinInject()
    val pageTranslationWebExtension: PageTranslationWebExtension = koinInject()
    val addressRepository: AddressRepository = koinInject()
    val formInputRepository: FormInputRepository = koinInject()
    val addressAutofillCoordinator: AddressAutofillCoordinator = koinInject()
    val formInputAutofillCoordinator: FormInputAutofillCoordinator = koinInject()
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    var showTabHistorySheet by remember { mutableStateOf(false) }

    val pendingPermissionsRef = remember {
        object {
            var pending: CompletableDeferred<Array<String>>? = null
        }
    }
    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.filterValues { it }.keys.toTypedArray()
        pendingPermissionsRef.pending?.complete(granted)
        pendingPermissionsRef.pending = null
    }

    val state = rememberBrowserTabScreenState(
        browserTab = browserTab,
        homepageUrl = homepageUrl,
        searchTemplate = searchTemplate,
        isSinglePageMode = webAppMode || customTabMode,
        webAppPinnedHost = webAppPinnedHost.takeIf { webAppMode },
        onWebAppCrossDomainNavigation = onWebAppCrossDomainNavigation.takeIf { webAppMode },
        onHistoryRecord = onHistoryRecord,
        onHistoryTitleUpdate = onHistoryTitleUpdate,
        onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
        externalDownloadDialogListener = externalDownloadDialogListener,
        externalTabInitialUrl = externalTabInitialUrl,
        onRequestAndroidPermissions = { permissions ->
            val alreadyGranted = permissions.filter {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isEmpty()) {
                alreadyGranted.toTypedArray()
            } else {
                val deferred = CompletableDeferred<Array<String>>()
                pendingPermissionsRef.pending = deferred
                requestPermissionsLauncher.launch(notGranted.toTypedArray())
                val newlyGranted = deferred.await()
                (alreadyGranted + newlyGranted).toTypedArray()
            }
        },
    )

    val toolbarColors = resolveBrowserToolbarColors(
        toolbarColor = state.toolbarColor,
        defaultToolbarColor = MaterialTheme.colorScheme.primaryContainer,
        isSystemDarkTheme = isSystemInDarkTheme(),
    )
    if (!state.isFullScreen) {
        StatusBarAppearanceEffect(toolbarColors.resolvedToolbarColor)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            if (!(customTabMode || webAppMode)) return@SideEffect
            val window = view.findActivity()?.window ?: return@SideEffect
            // CustomTab / WebApp は enableEdgeToEdge しないため、旧 OS ではシステム
            // ステータスバー背景が Theme.Browser のまま残る。ツールバー色に合わせる。
            window.statusBarColor = toolbarColors.resolvedToolbarColor.toArgb()
        }
    }

    if (!view.isInEditMode) {
        DisposableEffect(state.isFullScreen) {
            if (!state.isFullScreen) return@DisposableEffect onDispose {}
            val window = view.findActivity()?.window ?: return@DisposableEffect onDispose {}
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val dialogState = state.promptDialogState
    val webShareFilesState = state.webShareFilesState
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
    // 復元サイクルの世代。復元を始めるたびに更新し、前の世代が残した監視を無効化する。
    var surfaceRestoreGeneration by remember(session) { mutableIntStateOf(0) }
    // INVISIBLE にした surface の破棄を待っている間 true。破棄前に VISIBLE へ戻すと
    // 破棄と生成が合流して同じ surface のまま attach し直してしまう。
    var awaitingSurfaceDestroy by remember(session) { mutableStateOf(false) }
    // attach した時点の firstCompositeCount。attach より前に旧 surface から遅れて届いた
    // フレームを新しい surface の描画と取り違えないよう、ここを基準に増加を見る。
    var compositeCountAtAttach by remember(session) { mutableIntStateOf(0) }
    val addressAutofillDelegate = remember(session, addressAutofillCoordinator) {
        AddressAutofillDelegate(coordinator = addressAutofillCoordinator)
    }
    // observer は DisposableEffect のキーが変わらない限り再生成されない。色をキーにすると
    // テーマ変更のたびに effect が貼り直され、進行中の復元監視が世代ごと無効化されるため、
    // キーには含めずここから最新の色を読む。
    val currentResumeCoverColor by rememberUpdatedState(MaterialTheme.colorScheme.surface.toArgb())
    // LifecycleEventObserver は DisposableEffect のキーが変わらない限り再生成されないため、
    // ラムダ内で ON_PAUSE 時点の最新 IME 表示状態を読めるよう rememberUpdatedState で包む。
    val currentIsImeVisible by rememberUpdatedState(isImeVisible)

    val webShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            dialogState.confirmWebSharePrompt()
        } else {
            dialogState.dismissWebSharePrompt()
        }
    }

    val onFinishPendingWebShareFiles by rememberUpdatedState<(Boolean, String?) -> Unit>(
        { success, error -> webShareFilesState.finish(success, error) },
    )

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != WEB_SHARE_FILES_CHOSEN_ACTION) return
                val requestId = intent.getStringExtra(EXTRA_WEB_SHARE_FILES_REQUEST_ID) ?: return
                if (webShareFilesState.pending?.requestId != requestId) return
                onFinishPendingWebShareFiles(true, null)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WEB_SHARE_FILES_CHOSEN_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val webShareFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        if (webShareFilesState.pending?.completed == true) return@rememberLauncherForActivityResult
        if (activityResult.resultCode == Activity.RESULT_CANCELED) {
            onFinishPendingWebShareFiles(false, null)
        }
    }

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

    // 画像のみの要求で使うフォトピッカー（単一選択）Google フォトなどのクラウド写真も選択できる
    val singleVisualMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            dialogState.confirmFilePrompt(context, arrayOf(uri))
        } else {
            dialogState.dismissFilePrompt()
        }
    }

    val multipleVisualMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            dialogState.confirmFilePrompt(context, uris.toTypedArray())
        } else {
            dialogState.dismissFilePrompt()
        }
    }

    val pendingFilePrompt = dialogState.pendingFilePrompt
    LaunchedEffect(pendingFilePrompt) {
        val prompt = pendingFilePrompt ?: return@LaunchedEffect
        val mimeTypes = prompt.mimeTypes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        val isMultiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
        // フォトピッカーには撮影機能がないため、capture 指定時は従来のピッカーでカメラを選べるようにする
        val usesPhotoPicker = prompt.capture == GeckoSession.PromptDelegate.FilePrompt.Capture.NONE &&
            isAnyImageRequest(mimeTypes) &&
            ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
        val imageOnly = ActivityResultContracts.PickVisualMedia.ImageOnly
        when {
            usesPhotoPicker && isMultiple ->
                multipleVisualMediaLauncher.launch(PickVisualMediaRequest(imageOnly))

            usesPhotoPicker ->
                singleVisualMediaLauncher.launch(PickVisualMediaRequest(imageOnly))

            isMultiple ->
                multipleFilesLauncher.launch(mimeTypes)

            else ->
                singleFileLauncher.launch(mimeTypes)
        }
    }

    LaunchedEffect(dialogState) {
        dialogState.webShareLaunchChannel.receiveAsFlow().collect {
            val prompt = dialogState.pendingWebSharePrompt ?: return@collect
            val body = buildWebShareBody(prompt.text, prompt.uri)
            val subject = prompt.title?.takeIf { hasWebShareContent(it, null, null) }
            if (!canLaunchPlainTextShare(context, body, subject)) {
                dialogState.failWebSharePrompt()
                return@collect
            }
            val shareIntent = buildPlainTextShareIntent(body, subject)
            try {
                webShareLauncher.launch(Intent.createChooser(shareIntent, null))
            } catch (_: ActivityNotFoundException) {
                dialogState.failWebSharePrompt()
            }
        }
    }

    // 不安定なラムダキーによる DisposableEffect の再実行を防ぐ
    val currentOnCloseTab by rememberUpdatedState(onCloseTab)
    val currentOnOpenNewSessionRequest by rememberUpdatedState(onOpenNewSessionRequest)
    val currentOnOpenNewTabRequest by rememberUpdatedState(onOpenNewTabRequest)
    val currentOnReevaluateOpenerRetention by rememberUpdatedState(onReevaluateOpenerRetention)
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

    LaunchedEffect(state) {
        snapshotFlow { state.capturePreviewRequestCount }
            .collectLatest { count ->
                if (count == 0) return@collectLatest
                // GeckoView.capturePixels は Main スレッド必須。
                withContext(Dispatchers.Main.immediate) {
                    val gv = geckoView ?: return@withContext
                    state.captureTabPreview(gv)
                }
            }
    }

    // コンテンツプロセスのクラッシュ/kill (onCrash/onKill) を検知した際の即時復元。
    // 前面表示中 (lifecycle >= STARTED) のみ実行する。バックグラウンド中に検知した場合は
    // ここでは何もせず、ON_START 側の safety-net (attachSessionAfterStableSize 内の
    // !session.isOpen チェック) で次回復帰時に復元させる。
    LaunchedEffect(state, browserTab, browserSessionLifecycleController, lifecycleOwner) {
        snapshotFlow { state.sessionRecoveryRequestCount }
            .collectLatest { count ->
                if (count == 0) return@collectLatest
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    Log.d(
                        TAG_SURFACE_RESUME,
                        "sessionRecoveryRequestCount: バックグラウンドのため即時復元をスキップ",
                    )
                    return@collectLatest
                }
                withContext(Dispatchers.Main.immediate) {
                    browserSessionLifecycleController.restoreSession(browserTab)
                }
            }
    }

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
    // Column に .imePadding() が掛かっているため IME 表示中に pause すると GeckoView が
    // 縮む。ここで素直に setSession すると、setSession 直後の IME show/hide アニメーション
    // による resize が Mozilla 内部の SyncResumeResizeCompositor 経路でハングして GPU
    // プロセス kill に至る (1/5 程度の確率で観測)。
    //
    // そこで gv.height が連続して STABLE_FRAMES_THRESHOLD フレーム同じ値になるまで
    // setSession を遅延する。これで IME アニメーション完了後の安定したサイズで attach
    // できるため、setSession 直後の resize 経路を回避できる。最大 STABLE_TIMEOUT_MS で
    // 強制 attach する fallback も用意。待機中は coverUntilFirstPaint で覆い隠す。
    //
    // local function は前方参照不可なので attach → schedule → restore の順で定義する。
    fun attachSessionAfterStableSize(gecko: GeckoView) {
        // GeckoView.setSession は呼び出し時点の session に display を acquire し、その後
        // session が open されても貼り直さない。バックグラウンド中に onCrash/onKill で
        // コンテンツプロセスが失われた session を閉じたまま attach すると、新しい window に
        // Surface が渡らずコンポジタがフレームを出さない (画面が黒いまま固まる)。
        // 先に open→restoreState で復元してから attach する。
        if (!session.isOpen) {
            Log.w(
                TAG_SURFACE_RESUME,
                "attachSessionAfterStableSize: session closed (crash/kill) → setSession 前に restoreSession で復元" +
                    " session=${session.logKey()}",
            )
            browserSessionLifecycleController.restoreSession(browserTab)
        }
        gecko.setSession(session)
        addressAutofillDelegate.bind(session)
        if (session.isOpen) {
            session.setActive(true)
            // ポップアップを閉じて戻った直後は、拡張機能側のアクティブタブが閉じた子のまま残る
            browserSessionLifecycleController.notifyExtensionsActiveTab(session)
            // 別画面へ渡した子が閉じていれば、ここで opener の保持を解く
            currentOnReevaluateOpenerRetention()
        }
        compositeCountAtAttach = state.firstCompositeCount
        surfaceResumeState = SurfaceResumeState.ACTIVE
    }

    fun scheduleStableSizeAttach(
        gecko: GeckoView,
        generation: Int,
        recordedHeight: Int,
        stableCount: Int,
        startTimeMs: Long,
    ) {
        // OneShotPreDrawListener は描画が必要なフレームでしか発火せず、サイズ安定後に再描画の
        // トリガがないと stable check が進まず復帰が数十秒遅れる。postOnAnimation は Choreographer の
        // アニメーションフレームで毎 vsync 発火するため、UI 操作がなくても安定検出を進められる。
        gecko.postOnAnimation {
            // 前の復元サイクルが残した安定待ちは、新しいサイクルを古いサイズ記録で
            // 判定して早すぎる attach を招くため何もしない。
            if (generation != surfaceRestoreGeneration) return@postOnAnimation
            if (surfaceResumeState == SurfaceResumeState.ACTIVE) {
                Log.d(TAG_SURFACE_RESUME, "stable-check skipped: already ACTIVE")
                return@postOnAnimation
            }
            // ON_RESUME→ON_PAUSE の短時間遷移で遅延 callback が paused 中に動くのを防ぐ。
            // ON_PAUSE 側で surfaceResumeState は RELEASED に戻され、次回 ON_START で
            // 再度 scheduleStableSizeAttach が呼ばれる。
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Log.d(TAG_SURFACE_RESUME, "stable-check skipped: lifecycle not STARTED")
                surfaceResumeState = SurfaceResumeState.RELEASED
                return@postOnAnimation
            }
            // ON_PAUSE で releaseSession + INVISIBLE 済の場合、state は RELEASED に戻っているので
            // 復帰時の restoreSurfaceIfNeeded で再度 WAITING_STABLE に遷移しなおす。
            if (surfaceResumeState != SurfaceResumeState.WAITING_STABLE) {
                Log.d(
                    TAG_SURFACE_RESUME,
                    "stable-check skipped: state=$surfaceResumeState (not WAITING_STABLE)",
                )
                return@postOnAnimation
            }
            val h = gecko.height
            if (h == 0 || gecko.width == 0) {
                Log.d(TAG_SURFACE_RESUME, "stable-check: layout not settled, retry next frame")
                scheduleStableSizeAttach(gecko, generation, recordedHeight, stableCount, startTimeMs)
                return@postOnAnimation
            }
            val elapsed = SystemClock.elapsedRealtime() - startTimeMs
            if (h == recordedHeight) {
                val nextCount = stableCount + 1
                if (nextCount >= STABLE_FRAMES_THRESHOLD) {
                    Log.d(
                        TAG_SURFACE_RESUME,
                        "stable-check: stable confirmed (h=$h, frames=$nextCount, elapsed=${elapsed}ms)" +
                            " → setSession",
                    )
                    attachSessionAfterStableSize(gecko)
                } else {
                    scheduleStableSizeAttach(gecko, generation, h, nextCount, startTimeMs)
                }
            } else {
                if (elapsed >= STABLE_TIMEOUT_MS) {
                    Log.w(
                        TAG_SURFACE_RESUME,
                        "stable-check: timeout after ${elapsed}ms while size still changing" +
                            " (prev=$recordedHeight, current=$h) → 強制 setSession",
                    )
                    attachSessionAfterStableSize(gecko)
                } else {
                    Log.d(
                        TAG_SURFACE_RESUME,
                        "stable-check: size changed $recordedHeight → $h (elapsed=${elapsed}ms), reset counter",
                    )
                    scheduleStableSizeAttach(gecko, generation, h, 0, startTimeMs)
                }
            }
        }
    }

    fun restoreSurfaceIfNeeded(gecko: GeckoView, blankSurfaceRetryCount: Int) {
        Log.d(
            TAG_SURFACE_RESUME,
            "restoreSurfaceIfNeeded: state=$surfaceResumeState gv.size=${gecko.width}x${gecko.height}" +
                " measured=${gecko.measuredWidth}x${gecko.measuredHeight} visibility=${gecko.visibility}" +
                " session=${session.logKey()}",
        )
        if (surfaceResumeState != SurfaceResumeState.RELEASED) return
        if (awaitingSurfaceDestroy) {
            Log.d(TAG_SURFACE_RESUME, "restoreSurfaceIfNeeded: surface の破棄待ちのため何もしない")
            return
        }
        // ON_PAUSE で INVISIBLE にして Surface を破棄しているので VISIBLE に戻して
        // SurfaceView 内部の Surface を新規作成させる。
        if (gecko.visibility != View.VISIBLE) {
            Log.d(TAG_SURFACE_RESUME, "restoreSurfaceIfNeeded: visibility VISIBLE に戻す")
            gecko.visibility = View.VISIBLE
        }
        // stale フレームが一瞬表示されるのを防ぐため pre-draw 待ちより前に cover する。
        gecko.coverUntilFirstPaint(currentResumeCoverColor)
        surfaceResumeState = SurfaceResumeState.WAITING_STABLE
        surfaceRestoreGeneration++
        val generation = surfaceRestoreGeneration
        scheduleStableSizeAttach(
            gecko = gecko,
            generation = generation,
            recordedHeight = -1,
            stableCount = 0,
            startTimeMs = SystemClock.elapsedRealtime(),
        )
        // 復帰後の画面が黒いままだったときの作り直し。surface を破棄して張り直すだけでは
        // 描画が戻らない実機があるため、コンテンツプロセスごと畳んで開き直す。ページの
        // 状態は restoreSession の restoreState で戻る。
        fun recreateBlankSurface(retryCount: Int, reason: String) {
            Log.w(
                TAG_SURFACE_RESUME,
                "blank-surface: $reason のため session と surface を作り直す" +
                    " retry=${retryCount + 1} session=${session.logKey()}",
            )
            addressAutofillDelegate.unbindBeforeViewRelease(session)
            gecko.releaseSession()
            // window.open の関係に参加している session は閉じない。開き直せなくなるか、
            // opener との結び付きが失われる。
            if (browserSessionLifecycleController.canRecreateSession(browserTab)) {
                runCatching { session.close() }
            }
            gecko.visibility = View.INVISIBLE
            surfaceResumeState = SurfaceResumeState.RELEASED
            // surface の破棄は次の traversal で行われる。反映を待ってから
            // VISIBLE に戻さないと同じ surface に attach し直してしまう。
            awaitingSurfaceDestroy = true
            gecko.postDelayed(
                {
                    if (generation != surfaceRestoreGeneration) return@postDelayed
                    awaitingSurfaceDestroy = false
                    restoreSurfaceIfNeeded(gecko, retryCount + 1)
                },
                SURFACE_DESTROY_WAIT_MS,
            )
        }

        // attach しても Gecko 側のコンポジタが新しい surface にフレームを出さず、画面が
        // 黒いまま固まることがある。session は open のままなので他に検知手段がなく、
        // onFirstComposite が来たかどうかで判定して作り直す。
        // 猶予は attach (ACTIVE 遷移) を起点に数える。安定待ちに時間が掛かった分まで
        // 猶予から差し引くと、正常な復元を黒画面と誤判定してしまう。
        fun waitForFirstComposite(activeSinceMs: Long?) {
            gecko.postDelayed(
                {
                    // 前の復元サイクルが残した監視は、新しいサイクルの attach を
                    // 巻き添えに作り直してしまうため何もしない。
                    if (generation != surfaceRestoreGeneration) return@postDelayed
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        return@postDelayed
                    }
                    when (surfaceResumeState) {
                        // attach 待ち、またはオーバーレイ等の focus-only 離脱。どちらも
                        // surface は作り直されないので監視を続ける。猶予は ACTIVE に
                        // なってから数え直す。
                        SurfaceResumeState.WAITING_STABLE,
                        SurfaceResumeState.PAUSED_KEEP_SURFACE,
                        -> waitForFirstComposite(null)

                        SurfaceResumeState.ACTIVE -> {
                            val since = activeSinceMs ?: SystemClock.elapsedRealtime()
                            if (SystemClock.elapsedRealtime() - since < BLANK_SURFACE_TIMEOUT_MS) {
                                waitForFirstComposite(since)
                                return@postDelayed
                            }
                            if (blankSurfaceRetryCount >= BLANK_SURFACE_MAX_RETRY) {
                                Log.w(
                                    TAG_SURFACE_RESUME,
                                    "blank-surface: 作り直しても描画が戻らない。復旧を諦める" +
                                        " session=${session.logKey()}",
                                )
                                return@postDelayed
                            }
                            if (state.firstCompositeCount != compositeCountAtAttach) return@postDelayed
                            recreateBlankSurface(
                                retryCount = blankSurfaceRetryCount,
                                reason = "attach 後 ${BLANK_SURFACE_TIMEOUT_MS}ms で first composite が来ない",
                            )
                        }

                        // release 済み。次の復帰で新しい監視が始まる。
                        SurfaceResumeState.RELEASED -> Unit
                    }
                },
                BLANK_SURFACE_POLL_MS,
            )
        }
        waitForFirstComposite(null)
    }

    // pause からの復帰処理。
    // - RELEASED: surface 破棄済みのため再作成を伴う重い復元。
    // - PAUSED_KEEP_SURFACE: オーバーレイ等の focus-only 離脱から active を保持したまま
    //   戻ってきたケース。surface も session も生きているので state を ACTIVE に戻すだけ。
    //   setActive は呼ばない (可視のまま deactivate していないので再 activate も不要。
    //   呼ぶとコンポジタが一瞬クリアされ単一色フラッシュが出る)。
    fun resumeFromPauseIfNeeded(gecko: GeckoView) {
        when (surfaceResumeState) {
            SurfaceResumeState.RELEASED -> restoreSurfaceIfNeeded(gecko, blankSurfaceRetryCount = 0)

            SurfaceResumeState.PAUSED_KEEP_SURFACE -> {
                Log.d(
                    TAG_SURFACE_RESUME,
                    "resumeFromPauseIfNeeded: PAUSED_KEEP_SURFACE → ACTIVE (active 保持済み)" +
                        " session=${session.logKey()}",
                )
                surfaceResumeState = SurfaceResumeState.ACTIVE
            }

            SurfaceResumeState.ACTIVE, SurfaceResumeState.WAITING_STABLE -> Unit
        }
    }

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            val gv = geckoView
            Log.d(
                TAG_SURFACE_RESUME,
                "lifecycle event=$event state=$surfaceResumeState gv=${gv != null}" +
                    " gv.size=${gv?.width ?: -1}x${gv?.height ?: -1}" +
                    " session=${session.logKey()} sessionOpen=${session.isOpen}" +
                    " mediaKeep=${mediaWebExtension.shouldKeepSessionAttached(session)}",
            )
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // ON_STOP まで待つと surface 破棄→再作成時に GeckoView 内部の
                    // SurfaceHolder.Callback が Gecko compositor を自動 resume-resize させ、
                    // IME 由来の stale サイズで frame 産出 → BLAST reject → GPU プロセス kill
                    // というハングが発生する。ON_PAUSE 時点で releaseSession して session を
                    // GeckoView から detach しておけば、surface 再作成時の自動レンダリングを
                    // 抑止できる。
                    //
                    // ただしこのハング経路は .imePadding() による IME 由来の stale サイズが
                    // 前提のため、IME 非表示の pause (Gemini 等のアシスタントオーバーレイに
                    // よる focus-only 離脱を含む) では踏まない。この場合に release + INVISIBLE
                    // すると、Activity が可視のまま (ON_STOP が来ないまま) GeckoView だけが
                    // 真っ白になるため、surface と active を維持する (PAUSED_KEEP_SURFACE)。
                    // 完全に不可視になる ON_STOP 側で release する。
                    //
                    // capture preview は release 前に start する。capturePixels() は非同期
                    // GeckoResult を返すため release 直後に走るキャプチャ完了率は低下するが、
                    // ハング回避を優先する。
                    val target = geckoView
                    when {
                        mediaWebExtension.shouldKeepSessionAttached(session) ||
                            surfaceResumeState == SurfaceResumeState.RELEASED ||
                            surfaceResumeState == SurfaceResumeState.PAUSED_KEEP_SURFACE -> {
                            Log.d(
                                TAG_SURFACE_RESUME,
                                "ON_PAUSE skipped: state=$surfaceResumeState" +
                                    " mediaKeep=${mediaWebExtension.shouldKeepSessionAttached(session)}",
                            )
                        }

                        target == null -> {
                            // geckoView が更新されないまま ON_PAUSE が来ると release できず、
                            // 復帰時に session 付きで surface が再作成され BLAST reject が起きる。
                            // ここで検知できれば再現条件を絞り込めるため明示的に警告を残す。
                            Log.w(
                                TAG_SURFACE_RESUME,
                                "ON_PAUSE: geckoView=null のため releaseSession 不可。" +
                                    " 復帰時にハングする可能性あり session=${session.logKey()}",
                            )
                        }

                        surfaceResumeState == SurfaceResumeState.ACTIVE && !currentIsImeVisible -> {
                            // IME 非表示: stale サイズ resize のハング経路を踏まないため
                            // surface を維持し、オーバーレイ表示中の白画面化を防ぐ。
                            // view はまだ可視のため setActive(false) もしない (Mozilla の契約上
                            // deactivate は不可視時のみ。可視中に deactivate→再 activate すると
                            // コンポジタが一瞬クリアされ単一色フラッシュが出る)。
                            Log.d(
                                TAG_SURFACE_RESUME,
                                "ON_PAUSE: IME 非表示のため surface 維持 (active 保持)" +
                                    " gv.size=${target.width}x${target.height}",
                            )
                            // surface と compositor が生きているうちに capture する。
                            state.captureTabPreview(target)
                            surfaceResumeState = SurfaceResumeState.PAUSED_KEEP_SURFACE
                        }

                        else -> {
                            // IME 表示中の ACTIVE、または WAITING_STABLE 中（前回 resume の
                            // 安定待ちが完了する前に再度 pause した場合）は release する。
                            Log.d(
                                TAG_SURFACE_RESUME,
                                "ON_PAUSE: releaseSession + INVISIBLE 実行 gv.size=${target.width}x${target.height}",
                            )
                            // window.open のポップアップを別画面へ渡した opener は止めない。
                            // 止めると決済ウィンドウなどが window.opener 越しに親へ戻れなくなる。
                            browserSessionLifecycleController.pauseSession(browserTab)
                            // best-effort capture（非同期 GeckoResult、release 後に失敗する可能性あり）。
                            state.captureTabPreview(target)
                            // surface 再作成時の自動 compositor resume を防ぐため即 detach。
                            addressAutofillDelegate.unbindBeforeViewRelease(session)
                            target.releaseSession()
                            // View から外れると Gecko が opener を inactive にするため保持し直す。
                            currentOnReevaluateOpenerRetention()
                            // releaseSession だけでは Mozilla 側に古い surface 参照が残るらしく、
                            // 復帰時の setSession 直後に GPU プロセスが kill される事象が観測された。
                            // SurfaceView を INVISIBLE にすると内部 Surface を破棄するため、
                            // 復帰時の setSession を完全な新規 attach として扱わせる。
                            target.visibility = View.INVISIBLE
                            surfaceResumeState = SurfaceResumeState.RELEASED
                        }
                    }
                }

                Lifecycle.Event.ON_STOP -> {
                    session.flushSessionState()
                    // IME 表示中の pause は ON_PAUSE で release 済み (RELEASED)。
                    // media の場合は session 維持のため capture のみ実行する。
                    // TODO: media 再生継続中の session は release しないため、surface 再作成時の
                    //       SyncResumeResizeCompositor ハング経路を踏むリスクが残る。実機で
                    //       再現を確認したら、audio を殺さない形で compositor 再構築する手段
                    //       （releaseSession しても MediaSession 経由で音は継続する可能性が高い）
                    //       を検討する。
                    val target = geckoView ?: return@LifecycleEventObserver
                    when {
                        mediaWebExtension.shouldKeepSessionAttached(session) -> {
                            state.captureTabPreview(target)
                        }

                        surfaceResumeState == SurfaceResumeState.PAUSED_KEEP_SURFACE -> {
                            // IME 非表示の pause で surface を維持していたが、ON_STOP に
                            // 到達した = 完全に不可視化した (ホームボタン等)。ここで release
                            // せず session を attach したまま停止すると、復帰時に surface が
                            // session 付きで再作成され自動 resume-resize のハング経路を踏む。
                            // release して、復帰は RELEASED → fresh attach 経路に合流させる。
                            Log.d(
                                TAG_SURFACE_RESUME,
                                "ON_STOP: PAUSED_KEEP_SURFACE → releaseSession + INVISIBLE 実行" +
                                    " gv.size=${target.width}x${target.height}",
                            )
                            // 不可視になったので Mozilla の契約どおり deactivate してよい。
                            // ただし live popup の opener は JS を止めない。
                            browserSessionLifecycleController.pauseSession(browserTab)
                            addressAutofillDelegate.unbindBeforeViewRelease(session)
                            target.releaseSession()
                            // View から外れると Gecko が opener を inactive にするため保持し直す。
                            currentOnReevaluateOpenerRetention()
                            target.visibility = View.INVISIBLE
                            surfaceResumeState = SurfaceResumeState.RELEASED
                        }

                        else -> Unit
                    }
                }

                Lifecycle.Event.ON_START -> {
                    val gv = geckoView ?: return@LifecycleEventObserver
                    // ON_START は ON_STOP を経た復帰。PAUSED_KEEP_SURFACE のまま来たのは
                    // ON_STOP で release できなかったケースで、不可視の間に破棄された surface を
                    // session が掴んだままになり、復帰後もフレームが出ず黒いままになる。
                    // RELEASED に倒して surface 再作成からの復元経路へ合流させる。
                    if (surfaceResumeState == SurfaceResumeState.PAUSED_KEEP_SURFACE) {
                        Log.w(
                            TAG_SURFACE_RESUME,
                            "ON_START: PAUSED_KEEP_SURFACE のまま復帰したため release して作り直す" +
                                " session=${session.logKey()}",
                        )
                        addressAutofillDelegate.unbindBeforeViewRelease(session)
                        gv.releaseSession()
                        gv.visibility = View.INVISIBLE
                        surfaceResumeState = SurfaceResumeState.RELEASED
                        // surface の破棄が次の traversal で反映されるのを待ってから復元する。
                        awaitingSurfaceDestroy = true
                        val generation = surfaceRestoreGeneration
                        gv.postDelayed(
                            {
                                // 待っている間に Composable が破棄されていれば、捨てられた
                                // View と session には触らず、状態も書き換えない。
                                if (generation != surfaceRestoreGeneration) return@postDelayed
                                awaitingSurfaceDestroy = false
                                resumeFromPauseIfNeeded(gv)
                            },
                            SURFACE_DESTROY_WAIT_MS,
                        )
                        return@LifecycleEventObserver
                    }
                    resumeFromPauseIfNeeded(gv)
                }

                Lifecycle.Event.ON_RESUME -> {
                    val gv = geckoView ?: return@LifecycleEventObserver
                    resumeFromPauseIfNeeded(gv)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 破棄後に監視が生き残って View を触らないよう世代を進めて無効化する。
            surfaceRestoreGeneration++
        }
    }

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
        mediaWebExtension.registerSession(session, browserTab.tabId)
        onDispose {
            mediaWebExtension.unregisterSession(session)
        }
    }

    val mockLocationWebExtension: MockLocationWebExtension = koinInject()
    DisposableEffect(session, state, mockLocationWebExtension) {
        // iframe からの位置情報要求をトップレベルサイトの設定で制御するため、現在ページ URL を渡す
        mockLocationWebExtension.registerSession(session) { state.currentPageUrl }
        onDispose {
            mockLocationWebExtension.unregisterSession(session)
        }
    }

    val viewportScaleWebExtension: ViewportScaleWebExtension = koinInject()
    DisposableEffect(session, state, viewportScaleWebExtension) {
        viewportScaleWebExtension.registerSession(session) { scale ->
            state.visualViewportScale = scale
        }
        onDispose {
            viewportScaleWebExtension.unregisterSession(session)
        }
    }

    // ページ側へ接続トリガーを露出せず Native Messaging を開始できるよう、
    // 表示中セッションには content script より先に MessageDelegate を登録する。
    DisposableEffect(session, pageTranslationWebExtension) {
        pageTranslationWebExtension.registerSession(session)
        onDispose {
            pageTranslationWebExtension.unregisterSession(session)
        }
    }

    DisposableEffect(session, state, findInPageWebExtension) {
        findInPageWebExtension.registerSession(session) { current, total, error ->
            // 正規表現モードでないときに届いた遅延結果は無視する
            if (!state.findInPage.isRegex) return@registerSession
            state.findInPage.onRegexSearchResult(current = current, total = total, error = error)
        }
        onDispose {
            findInPageWebExtension.unregisterSession(session)
        }
    }

    // TwitterShareWebExtension のセッション登録。
    // Twitter/X の共有リンク・ボタンのクリックを OS の共有シートに振り替える
    val twitterShareWebExtension: TwitterShareWebExtension = koinInject()
    DisposableEffect(session, state, twitterShareWebExtension) {
        twitterShareWebExtension.registerSession(session) { data ->
            state.shareText(data.toShareText())
        }
        onDispose {
            twitterShareWebExtension.unregisterSession(session)
        }
    }

    // Web Share API v2 (files) のワークアラウンド。Gecko 未対応時に拡張ポリフィルから共有シートを起動する
    val webShareFilesWebExtension: WebShareFilesWebExtension = koinInject()
    DisposableEffect(session, webShareFilesWebExtension) {
        webShareFilesWebExtension.registerSession(session) { request, geckoResult ->
            try {
                // 応答が返らないまま残った要求で以降の共有を止めないよう、
                // 進行中の要求は中断して新しい要求を受け付ける
                webShareFilesState.finish(
                    success = false,
                    error = "新しい共有リクエストに置き換えられました",
                )
                val payloads = decodeWebShareFiles(request.files)
                if (payloads == null) {
                    geckoResult.complete(
                        JSONObject()
                            .put("success", false)
                            .put("error", "共有データの準備に失敗しました")
                            .put("errorName", "NotAllowedError"),
                    )
                    return@registerSession
                }
                val prepared = prepareWebShareFilesIntent(
                    context = context,
                    requestId = request.requestId,
                    title = request.title,
                    text = request.text,
                    url = request.url,
                    files = payloads,
                )
                if (prepared == null || !canLaunchWebShareFilesIntent(context, prepared.intent)) {
                    prepared?.cacheDir?.deleteRecursively()
                    geckoResult.complete(
                        JSONObject()
                            .put("success", false)
                            .put("error", "共有できるアプリがありません")
                            .put("errorName", "NotAllowedError"),
                    )
                    return@registerSession
                }
                webShareFilesState.pending = WebShareFilesState.Pending(
                    requestId = request.requestId,
                    geckoResult = geckoResult,
                    cacheDir = prepared.cacheDir,
                )
                webShareFilesLauncher.launch(
                    buildWebShareFilesChooserIntent(
                        context = context,
                        shareIntent = prepared.intent,
                        requestId = request.requestId,
                    ),
                )
            } catch (_: ActivityNotFoundException) {
                geckoResult.complete(
                    JSONObject()
                        .put("success", false)
                        .put("error", "共有できるアプリがありません")
                        .put("errorName", "NotAllowedError"),
                )
            } catch (_: IllegalArgumentException) {
                geckoResult.complete(
                    JSONObject()
                        .put("success", false)
                        .put("error", "共有データの準備に失敗しました")
                        .put("errorName", "NotAllowedError"),
                )
            }
        }
        onDispose {
            webShareFilesWebExtension.unregisterSession(session)
            webShareFilesState.cleanupOnDispose()
        }
    }

    // 拡張機能のツールバーアクション (browserAction/pageAction) のセッション登録。
    // これを設定しないと拡張機能がタブごとに出すアイコン・ポップアップを受け取れない
    val webExtensionActionController: WebExtensionActionController = koinInject()
    DisposableEffect(session, state, webExtensionActionController) {
        webExtensionActionController.registerSession(session) { popup ->
            state.extensionActionPopup = popup
        }
        onDispose {
            webExtensionActionController.unregisterSession(session)
        }
    }

    DisposableEffect(session, state) {
        val devToolsWebExtension = state.devToolsWebExtension
        devToolsWebExtension.registerSession(session) { focusedInput ->
            state.devToolsFocusedInput = focusedInput
        }
        onDispose {
            devToolsWebExtension.unregisterSession(session)
        }
    }

    // NetworkLogWebExtension のセッション登録。
    // 通信ログ自体はバックグラウンドスクリプトが常時収集しており、
    // ここではセッションと webRequest の tabId を対応付けるために登録する
    val networkLogWebExtension: NetworkLogWebExtension = koinInject()
    DisposableEffect(session, networkLogWebExtension) {
        networkLogWebExtension.registerSession(session)
        onDispose {
            networkLogWebExtension.unregisterSession(session)
        }
    }

    DisposableEffect(
        session,
        state,
        browserTab,
        mediaWebExtension,
        addressRepository,
        formInputRepository,
        addressAutofillCoordinator,
        formInputAutofillCoordinator,
    ) {
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
        addressAutofillDelegate.bind(session)
        addressAutofillCoordinator.attach(
            session = session,
            host = dialogState,
            addressRepository = addressRepository,
        )
        formInputAutofillCoordinator.attach(
            session = session,
            host = dialogState,
            formInputRepository = formInputRepository,
        )
        // MediaSession の初回イベントを取りこぼさないよう、ページ読み込み前に delegate を設定する。
        session.mediaSessionDelegate = mediaSessionDelegate

        browserSessionLifecycleController.restoreSession(browserTab)

        onDispose {
            addressAutofillCoordinator.detach(session)
            formInputAutofillCoordinator.detach(session)
            browserTab.detachSessionCallbacks()
            session.promptDelegate = null
            addressAutofillDelegate.restoreWrapped(session)
            if (session.mediaSessionDelegate === mediaSessionDelegate &&
                !mediaWebExtension.shouldKeepSessionAttached(session)
            ) {
                session.mediaSessionDelegate = null
            }
            // View が外れたあとに Gecko が opener を inactive にするため、
            // 次メッセージで live popup の opener を再 active する。
            currentOnReevaluateOpenerRetention()
        }
    }

    // 初回の通知が attach 前に捨てられないよう、attach する DisposableEffect より後に置く。
    // 分割画面では通常ブラウザと Custom Tab が同時に RESUMED のまま残り、操作する
    // ペインを切り替えても ON_RESUME は再通知されない。ウィンドウフォーカスで前面の
    // 画面を判断し、セッションを伴わない住所取得の宛先にする。
    val windowFocusOwnerView = LocalView.current
    DisposableEffect(windowFocusOwnerView, session, addressAutofillCoordinator) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            addressAutofillCoordinator.onWindowFocusChanged(session, hasFocus)
        }
        addressAutofillCoordinator.onWindowFocusChanged(
            session,
            windowFocusOwnerView.hasWindowFocus(),
        )
        windowFocusOwnerView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            windowFocusOwnerView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    // 新規タブの初回ロードは GeckoView のサイズ確定後に実行する。
    // setSession 直後の未確定 viewport でロードすると、画像単体表示 (ImageDocument) の
    // shrink-to-fit スケールが誤計算され、画像が小さく低解像度で表示されるため。
    // geckoView は AndroidView factory で後から確定するので key にして再起動させる。
    LaunchedEffect(session, browserTab, geckoView) {
        val gv = geckoView ?: return@LaunchedEffect
        if (!browserSessionLifecycleController.hasPendingInitialLoad(browserTab)) {
            return@LaunchedEffect
        }
        val startTimeMs = SystemClock.elapsedRealtime()
        fun scheduleInitialLoad() {
            gv.postOnAnimation {
                if (!browserSessionLifecycleController.hasPendingInitialLoad(browserTab)) {
                    return@postOnAnimation
                }
                val elapsed = SystemClock.elapsedRealtime() - startTimeMs
                // サイズ未確定の間は次フレームへ持ち越す。レイアウトが進まない異常系で
                // 白画面のままにならないよう STABLE_TIMEOUT_MS 経過後は強制ロードする
                if ((gv.width == 0 || gv.height == 0) && elapsed < STABLE_TIMEOUT_MS) {
                    scheduleInitialLoad()
                    return@postOnAnimation
                }
                browserSessionLifecycleController.performInitialLoadIfPending(browserTab)
            }
        }
        scheduleInitialLoad()
    }

    DisposableEffect(session, enableTabUi, searchTemplate, formInputAutofillCoordinator) {
        val activity = context.findActivity()
        if (activity == null) {
            return@DisposableEffect onDispose {}
        }
        val delegate = object : BasicSelectionActionDelegate(activity) {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val result = super.onCreateActionMode(mode, menu)

                val selectionFlags = mSelection?.flags ?: 0
                val isEditableField =
                    selectionFlags and SelectionActionDelegate.FLAG_IS_EDITABLE != 0
                val isPasswordField =
                    selectionFlags and SelectionActionDelegate.FLAG_IS_PASSWORD != 0
                if (isEditableField && !isPasswordField) {
                    menu.add(Menu.NONE, MENU_ID_SAVE_FORM_INPUT, Menu.NONE, "入力欄を保存")
                }

                val text = mSelection?.text?.trim().orEmpty()
                if (text.isNotBlank()) {
                    val isUrl = text.startsWith("http://") ||
                        text.startsWith("https://") ||
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
                when (item.itemId) {
                    MENU_ID_SAVE_FORM_INPUT -> {
                        formInputAutofillCoordinator.requestSaveFocusedField(session)
                        mode.finish()
                        return true
                    }
                }
                val text = mSelection?.text?.trim()
                    ?: return super.onActionItemClicked(mode, item)
                when (item.itemId) {
                    MENU_ID_SEARCH -> {
                        val url = searchTemplate.replace(
                            "%s",
                            URLEncoder.encode(text, "UTF-8"),
                        )
                        if (enableTabUi) {
                            currentOnOpenNewTabRequest(url, null)
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
                            currentOnOpenNewTabRequest(url, null)
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

    // webAppMode で戻る先が無い場合はバックを消費しない。
    // ハンドラを無効化してシステムに委ねることで、メインアプリと同様に予測型バック
    // （ホーム画面へ縮小していくアニメーション）を発生させ、そのまま Activity を終了させる。
    PredictiveBackHandler(enabled = state.isFullScreen || state.findInPage.isVisible || state.isUrlInputFocused || state.canGoBack) { progress ->
        state.isBackGestureInProgress = true
        try {
            progress.collect {}
            when {
                state.isFullScreen -> state.exitFullScreen()
                state.findInPage.isVisible -> state.findInPage.close()
                state.isUrlInputFocused -> closeUrlInput(true)
                state.canGoBack -> state.onGoBack()
            }
        } finally {
            state.isBackGestureInProgress = false
        }
    }

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
            .then(
                if (state.isFullScreen) {
                    Modifier
                } else {
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing
                                .exclude(WindowInsets.ime)
                                .exclude(WindowInsets.navigationBars)
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .then(
                            // IME 表示中にナビバー分も確保すると、キーボード直上に余分な帯ができる。
                            // 住所オートフィル候補バーは imeAboveNavigationBarsPadding で IME 全高ぶん上げる。
                            if (isImeVisible) {
                                Modifier
                            } else {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                                )
                            },
                        )
                },
            ),
    ) {
        if (state.isFullScreen) {
            // フルスクリーン中はツールバー・翻訳バー・検索バーを出さない
        } else if (state.findInPage.isVisible) {
            FindInPageBar(
                query = state.findInPage.query,
                matchCurrent = state.findInPage.matchCurrent,
                matchTotal = state.findInPage.matchTotal,
                isRegex = state.findInPage.isRegex,
                queryError = state.findInPage.queryError,
                onQueryChange = state.findInPage::onQueryChange,
                onNext = state.findInPage::findNext,
                onPrevious = state.findInPage::findPrevious,
                onClose = state.findInPage::close,
                onToggleRegex = state.findInPage::toggleRegex,
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
                    isPageLoading = state.isPageLoading,
                    onStopLoading = state::onStopLoading,
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
                    onTranslatePage = { state.translation.onTranslate(translationProvider, geminiNanoModelKey) },
                    onShare = state::sharePage,
                    onFindInPage = state.findInPage::open,
                    onAddToHomeScreen = state::requestAddToHomeScreen,
                    showAddToHomeScreen = !webAppMode,
                    onOpenInBrowser = onOpenInBrowser?.let { callback ->
                        { callback(state.currentPageUrl) }
                    },
                    onOpenSiteSettings = onOpenSiteSettings?.let { callback ->
                        { callback(state.currentPageUrl) }
                    },
                    pageZoomPercent = state.pageZoomPercent,
                    onPageZoomIn = state::pageZoomIn,
                    onPageZoomOut = state::pageZoomOut,
                    onResetPageZoom = state::resetPageZoom,
                    showCloseButton = customTabMode,
                    showHome = webAppMode,
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
                            val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
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
                    onLongClickUrl = state::copyCurrentPageUrl,
                    showInstallExtensionItem = showInstallExtensionItem && state.showInstallExtensionItem,
                    onInstallExtension = { onInstallExtensionRequest(state.currentPageUrl) },
                    onOpenSettings = onOpenSettings,
                    onOpenSiteSettings = onOpenSiteSettings?.let { callback ->
                        { callback(state.currentPageUrl) }
                    },
                    onOpenDownloads = onOpenDownloads,
                    onOpenDevTools = state::openDevTools,
                    onShare = state::sharePage,
                    tabCount = tabCount,
                    showTabActions = enableTabUi,
                    onOpenTabs = {
                        if (enableTabUi) {
                            val gv = geckoView
                            if (gv != null) {
                                runCatching { state.flushAndCaptureForTabSwitch(gv) }
                            }
                            onOpenTabs()
                        }
                    },
                    isPcMode = state.isPcMode,
                    onPcModeToggle = state::togglePcMode,
                    onFindInPage = state.findInPage::open,
                    toolbarColor = state.toolbarColor,
                    onHome = state::onHome,
                    onForward = state::onGoForward,
                    canGoForward = state.canGoForward,
                    onBack = state::onGoBack,
                    canGoBack = state.canGoBack,
                    onLongPressHistory = { showTabHistorySheet = true },
                    onRefresh = state::onRefresh,
                    onSuperRefresh = state::onSuperRefresh,
                    isPageLoading = state.isPageLoading,
                    onStopLoading = state::onStopLoading,
                    onTranslatePage = { state.translation.onTranslate(translationProvider, geminiNanoModelKey) },
                    pageZoomPercent = state.pageZoomPercent,
                    onPageZoomIn = state::pageZoomIn,
                    onPageZoomOut = state::pageZoomOut,
                    onResetPageZoom = state::resetPageZoom,
                    extensionActions = state.extensionActions,
                    extensionActionScrollState = state.extensionActionScrollState,
                    onExtensionActionMove = state::onExtensionActionMove,
                    onExtensionActionMoveEnd = state::onExtensionActionMoveEnd,
                    onExtensionActionMoveCancel = state::onExtensionActionMoveCancel,
                    onHorizontalDrag = onToolbarHorizontalDrag,
                    onHorizontalDragEnd = {
                        // タブ切替スワイプになる可能性があるため、現在のタブのプレビューを事前にキャプチャする
                        val gv = geckoView
                        if (gv != null) {
                            runCatching { state.flushAndCaptureForTabSwitch(gv) }
                        }
                        onToolbarDragEnd()
                    },
                    onAddToHomeScreen = state::requestAddToHomeScreen,
                )
            }
            val detectedLang = state.translation.detectedPageLanguage
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
                state = state.translation.state,
                onRevert = state.translation::onRevert,
                onDismissError = state.translation::onDismissError,
                errorMessage = state.translation.errorMessage,
                progress = state.translation.progress,
                fromLanguage = state.translation.fromLanguage,
                toLanguage = state.translation.toLanguage,
                fromLanguageOptions = languageOptions,
                toLanguageOptions = languageOptions,
                onFromLanguageSelected = { lang ->
                    state.translation.onRetranslate(
                        translationProvider,
                        geminiNanoModelKey = geminiNanoModelKey,
                        fromLanguage = lang,
                        toLanguage = state.translation.toLanguage ?: TranslationPriorityLanguage.TO,
                    )
                },
                onToLanguageSelected = { lang ->
                    state.translation.onRetranslate(
                        translationProvider,
                        geminiNanoModelKey = geminiNanoModelKey,
                        fromLanguage = state.translation.fromLanguage,
                        toLanguage = lang,
                    )
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
                },
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
                // edge-to-edge ではどのモードでもウィンドウが縮まないため、
                // 候補リストが IME に隠れないよう自前で余白を取る。
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            )

            val autofillBar = dialogState.addressAutofillBar
            if (
                autofillBar != null &&
                autofillBar.items.isNotEmpty() &&
                !state.isUrlInputFocused &&
                !state.findInPage.isVisible &&
                !state.isFullScreen
            ) {
                // GeckoView は IME でリサイズしない。バーだけ IME 上へ上げる。
                AddressAutofillSuggestionBar(
                    uiState = autofillBar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imeAboveNavigationBarsPadding(),
                )
            }
        }
        BrowserTabDialogLayer(
            state = state,
            dialogState = dialogState,
            enableTabUi = enableTabUi,
            customTabMode = customTabMode || webAppMode,
            onOpenNewTabRequest = currentOnOpenNewTabRequest,
            onOpenFile = { fileUri ->
                val uri = fileUri.toUri()
                val mimeType = context.contentResolver.getType(uri) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                runCatching { context.startActivity(intent) }
            },
        )
    }

    state.addToHomeScreenState?.let { addToHomeScreenState ->
        AddToHomeScreenDialog(
            url = addToHomeScreenState.url,
            title = addToHomeScreenState.title,
            favicon = addToHomeScreenState.favicon,
            isIconLoading = addToHomeScreenState.isIconLoading,
            onDismiss = state::dismissAddToHomeScreen,
        )
    }

    state.extensionActionPopup?.let { popup ->
        ExtensionActionPopupDialog(
            popup = popup,
            onDismissRequest = state::dismissExtensionActionPopup,
        )
    }

    if (state.showDevTools) {
        DevToolsDialog(
            focusedInput = state.devToolsFocusedInput,
            onCopyFocusedInputId = state::copyFocusedInputId,
            onOpenNetworkLog = state::openNetworkLog,
            onOpenConsole = state::openDevToolsConsole,
            onDismiss = state::closeDevTools,
        )
    }

    if (state.showNetworkLog) {
        NetworkLogDialog(
            uiState = rememberNetworkLogUiState(
                session = session,
                onDismiss = state::closeNetworkLog,
            ),
        )
    }

    if (state.showDevToolsConsole) {
        DevToolsConsoleDialog(
            uiState = rememberDevToolsConsoleUiState(
                session = session,
                onDismiss = state::closeDevToolsConsole,
            ),
        )
    }

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
                            },
                        )
                        .clickable { onNavigateTo(index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = entry.title.ifBlank { entry.uri },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.uri,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
 * - RELEASED: ON_PAUSE (IME 表示中) または ON_STOP で releaseSession() 済。
 *   ON_START で復元処理が必要。
 * - WAITING_STABLE: 復元の preDraw ループ中で gv.height が安定するのを待っている。
 *   IME アニメーション中の resize で GPU プロセスが kill される現象を避けるため、
 *   サイズが連続フレーム同じになるまで setSession を遅延する。
 * - PAUSED_KEEP_SURFACE: IME 非表示の ON_PAUSE (Gemini 等のアシスタントオーバーレイに
 *   よる focus-only 離脱を含む)。GeckoView は縮んでおらず復帰時の stale サイズ resize
 *   ハング経路を踏まないため surface は破棄せず維持する。オーバーレイ中はまだ画面に
 *   見えているため session も active のまま保持し、白画面化とフラッシュを防ぐ。
 *   ON_RESUME で ACTIVE に戻るだけの軽い復帰。ON_STOP に到達した (完全に不可視化した)
 *   場合はそこで release して RELEASED に遷移する。
 */
private enum class SurfaceResumeState {
    ACTIVE,
    RELEASED,
    WAITING_STABLE,
    PAUSED_KEEP_SURFACE,
}

sealed interface GeckoBrowserTabTestTags {
    val id: String
    val testTag get() = "${GeckoBrowserTabTestTags::class.java.name}#$id"

    object GeckoContainer : GeckoBrowserTabTestTags {
        override val id = "gecko_container"

        fun testTag(isForeground: Boolean): String =
            if (isForeground) "$testTag#foreground" else testTag
    }
}

private const val URL_BAR_IME_HIDE_GRACE_MS = 700L

/**
 * パスワードマネージャ等の Activity 切替復帰時に GeckoView の surface 復元で起きる
 * BLAST reject → GPU プロセス kill 経路を診断するためのログタグ。
 */
private const val TAG_SURFACE_RESUME = "GeckoSurfaceResume"

/**
 * 復帰時に gv.height が何フレーム連続で同じ値なら「安定した」とみなして setSession するか。
 * IME アニメーションは概ね 200-300ms かかるため、60fps で 3 フレーム (=約 50ms) 連続
 * 同じであれば、その時点で当面サイズ変化はないと判断する。
 */
private const val STABLE_FRAMES_THRESHOLD = 3

/**
 * 復帰時のサイズ安定待ちのタイムアウト。これを超えるとサイズが安定していなくても強制的に
 * setSession する。Web 入力欄の表示遅延と GPU kill 防止のトレードオフ。
 */
private const val STABLE_TIMEOUT_MS = 1000L

/**
 * attach 後にコンポジタの最初のフレーム (onFirstComposite) を待つ時間。これを過ぎても
 * 来なければ surface にフレームが供給されていないとみなし、作り直して attach し直す。
 */
private const val BLANK_SURFACE_TIMEOUT_MS = 1500L

/** 黒いままの surface を作り直す上限回数。1 回の復帰サイクルごとに数え直す。 */
private const val BLANK_SURFACE_MAX_RETRY = 2

/** first composite の到着と attach 完了を見に行く間隔。 */
private const val BLANK_SURFACE_POLL_MS = 250L

/**
 * INVISIBLE にした SurfaceView の surface が破棄されるまでの待ち時間。破棄は次の
 * traversal で行われるため、同じコールスタックで VISIBLE に戻すと破棄が起きない。
 */
private const val SURFACE_DESTROY_WAIT_MS = 100L

private fun GeckoSession.logKey(): String = Integer.toHexString(System.identityHashCode(this))

private const val MENU_ID_SEARCH = 0x10001
private const val MENU_ID_OPEN = 0x10002
private const val MENU_ID_SAVE_FORM_INPUT = 0x10003

/**
 * 任意の画像を求める要求かどうか。
 * image/png のような具体的なサブタイプ指定はフォトピッカーでは制約を表現できず、
 * SVG など MediaStore に載らない形式も選べなくなるため、従来のファイルピッカーに任せる。
 */
private fun isAnyImageRequest(mimeTypes: Array<String>): Boolean {
    return mimeTypes.any { it == "image/*" } && mimeTypes.all { it.startsWith("image/") }
}

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
        if (resultCode != Activity.RESULT_OK || intent == null) return listOf()
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

/**
 * IME 表示中は親 Column が navigationBars の bottom padding を外すため、
 * 候補バーは IME の全高ぶん上げる。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modifier.imeAboveNavigationBarsPadding(): Modifier {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    return padding(bottom = with(density) { imeBottomPx.toDp() })
}

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

package net.matsudamper.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.net.toUri
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.media.GeckoMediaSessionDelegate
import net.matsudamper.browser.media.MediaWebExtension
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.TwitterShareWebExtension
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
    onOpenSiteSettings: ((currentUrl: String) -> Unit)?,
    onOpenDownloads: (() -> Unit)?,
    onOpenTabs: () -> Unit,
    onOpenNewSessionRequest: (String) -> GeckoSession?,
    onOpenNewTabRequest: (url: String, referrerUrl: String?) -> Unit,
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

    // Androidランタイムパーミッション要求用（マイク・カメラ等）
    val pendingPermissionsRef = remember {
        object {
            var pending: CompletableDeferred<Array<String>>? = null
        }
    }
    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
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
        onHistoryRecord = onHistoryRecord,
        onHistoryTitleUpdate = onHistoryTitleUpdate,
        onRequestDownloadNotificationPermission = onRequestDownloadNotificationPermission,
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

    // フルスクリーン時にシステムバーを非表示にする
    if (!view.isInEditMode) {
        DisposableEffect(state.isFullScreen) {
            if (!state.isFullScreen) return@DisposableEffect onDispose {}
            val window = (view.context as Activity).window
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
    // LifecycleEventObserver は DisposableEffect のキーが変わらない限り再生成されないため、
    // ラムダ内で ON_PAUSE 時点の最新 IME 表示状態を読めるよう rememberUpdatedState で包む。
    val currentIsImeVisible by rememberUpdatedState(isImeVisible)

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

    // ページの初回描画・ロード完了の度にプレビューキャプチャを実行する
    LaunchedEffect(state) {
        snapshotFlow { state.capturePreviewRequestCount }
            .collectLatest { count ->
                if (count == 0) return@collectLatest
                /**
                 * GeckoView.capturePixels は Main スレッド必須。
                 */
                withContext(Dispatchers.Main.immediate) {
                    geckoView?.also { gv -> state.captureTabPreview(gv) }
                }
            }
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
        gecko.setSession(session)
        session.setActive(true)
        surfaceResumeState = SurfaceResumeState.ACTIVE
    }

    fun scheduleStableSizeAttach(
        gecko: GeckoView,
        recordedHeight: Int,
        stableCount: Int,
        startTimeMs: Long,
    ) {
        // 旧実装は OneShotPreDrawListener を使っていたが、preDraw は描画が必要なフレーム
        // でしか発火しないため、サイズ安定後に画面の再描画トリガがないと stable check が
        // 進まず復帰が遅延する事象が観測された (再現で 31 秒待たされた)。
        // postOnAnimation は Choreographer のアニメーションフレームで毎 vsync 発火する
        // ため、UI 操作がなくても安定検出を進められる。
        gecko.postOnAnimation {
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
                scheduleStableSizeAttach(gecko, recordedHeight, stableCount, startTimeMs)
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
                    scheduleStableSizeAttach(gecko, h, nextCount, startTimeMs)
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
                    scheduleStableSizeAttach(gecko, h, 0, startTimeMs)
                }
            }
        }
    }

    fun restoreSurfaceIfNeeded(gecko: GeckoView) {
        Log.d(
            TAG_SURFACE_RESUME,
            "restoreSurfaceIfNeeded: state=$surfaceResumeState gv.size=${gecko.width}x${gecko.height}" +
                " measured=${gecko.measuredWidth}x${gecko.measuredHeight} visibility=${gecko.visibility}" +
                " session=${session.logKey()}",
        )
        if (surfaceResumeState != SurfaceResumeState.RELEASED) return
        // ON_PAUSE で INVISIBLE にして Surface を破棄しているので VISIBLE に戻して
        // SurfaceView 内部の Surface を新規作成させる。
        if (gecko.visibility != View.VISIBLE) {
            Log.d(TAG_SURFACE_RESUME, "restoreSurfaceIfNeeded: visibility VISIBLE に戻す")
            gecko.visibility = View.VISIBLE
        }
        // stale フレームが一瞬表示されるのを防ぐため pre-draw 待ちより前に cover する。
        gecko.coverUntilFirstPaint(resumeCoverColor)
        surfaceResumeState = SurfaceResumeState.WAITING_STABLE
        scheduleStableSizeAttach(
            gecko = gecko,
            recordedHeight = -1,
            stableCount = 0,
            startTimeMs = SystemClock.elapsedRealtime(),
        )
    }

    // pause からの復帰処理。
    // - RELEASED: surface 破棄済みのため再作成を伴う重い復元。
    // - PAUSED_KEEP_SURFACE: オーバーレイ等の focus-only 離脱から active を保持したまま
    //   戻ってきたケース。surface も session も生きているので state を ACTIVE に戻すだけ。
    //   setActive は呼ばない (可視のまま deactivate していないので再 activate も不要。
    //   呼ぶとコンポジタが一瞬クリアされ単一色フラッシュが出る)。
    fun resumeFromPauseIfNeeded(gecko: GeckoView) {
        when (surfaceResumeState) {
            SurfaceResumeState.RELEASED -> restoreSurfaceIfNeeded(gecko)
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

    DisposableEffect(lifecycleOwner, session, resumeCoverColor) {
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
                    // 完全に不可視になる ON_STOP 側で従来どおり release する。
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
                            // 安定待ちが完了する前に再度 pause した場合）は従来どおり release。
                            Log.d(
                                TAG_SURFACE_RESUME,
                                "ON_PAUSE: releaseSession + INVISIBLE 実行 gv.size=${target.width}x${target.height}",
                            )
                            session.setActive(false)
                            // best-effort capture（非同期 GeckoResult、release 後に失敗する可能性あり）。
                            state.captureTabPreview(target)
                            // surface 再作成時の自動 compositor resume を防ぐため即 detach。
                            target.releaseSession()
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
                    // media の場合は session 維持のため capture のみ実行（従来どおり）。
                    // TODO: media 再生継続中の session は release しないため、surface 再作成時の
                    //       SyncResumeResizeCompositor ハング経路を踏むリスクが残る。実機で
                    //       再現を確認したら、audio を殺さない形で compositor 再構築する手段
                    //       （releaseSession しても MediaSession 経由で音は継続する可能性が高い）
                    //       を検討する。
                    geckoView?.also { target ->
                        when {
                            mediaWebExtension.shouldKeepSessionAttached(session) -> {
                                state.captureTabPreview(target)
                            }
                            surfaceResumeState == SurfaceResumeState.PAUSED_KEEP_SURFACE -> {
                                // IME 非表示の pause で surface を維持していたが、ON_STOP に
                                // 到達した = 完全に不可視化した (ホームボタン等)。ここで release
                                // せず session を attach したまま停止すると、復帰時に surface が
                                // session 付きで再作成され自動 resume-resize のハング経路を踏む
                                // (revert された #398 の STOPPED_KEEP_SURFACE はこれが原因と推測)。
                                // 従来どおり release して、復帰は実績のある RELEASED →
                                // fresh attach 経路に合流させる。
                                Log.d(
                                    TAG_SURFACE_RESUME,
                                    "ON_STOP: PAUSED_KEEP_SURFACE → releaseSession + INVISIBLE 実行" +
                                        " gv.size=${target.width}x${target.height}",
                                )
                                // 不可視になったので Mozilla の契約どおり deactivate してよい。
                                session.setActive(false)
                                target.releaseSession()
                                target.visibility = View.INVISIBLE
                                surfaceResumeState = SurfaceResumeState.RELEASED
                            }
                            else -> Unit
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    geckoView?.also(::resumeFromPauseIfNeeded)
                }
                Lifecycle.Event.ON_RESUME -> {
                    geckoView?.also(::resumeFromPauseIfNeeded)
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

    // DevToolsWebExtension のセッション登録（フォーカス中の入力要素情報の通知）
    DisposableEffect(session, state) {
        val devToolsWebExtension = state.devToolsWebExtension
        devToolsWebExtension.registerSession(session) { focusedInput ->
            state.devToolsFocusedInput = focusedInput
        }
        onDispose {
            devToolsWebExtension.unregisterSession(session)
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

    // Back handler (when 分岐で優先度を制御: showFindInPage > isUrlInputFocused > canGoBack)
    // webAppMode で上記いずれにも該当しない（これ以上戻れない）場合はバックを消費しない。
    // ハンドラを無効化してシステムに委ねることで、メインアプリと同様に予測型バック
    // （ホーム画面へ縮小していくアニメーション）を発生させ、そのまま Activity を終了させる。
    PredictiveBackHandler(enabled = state.isFullScreen || state.showFindInPage || state.isUrlInputFocused || state.canGoBack) { progress ->
        state.onBackGestureStarted()
        try {
            progress.collect {}
            when {
                state.isFullScreen -> state.exitFullScreen()
                state.showFindInPage -> state.closeFindInPage()
                state.isUrlInputFocused -> closeUrlInput(true)
                state.canGoBack -> state.onGoBack()
            }
        } finally {
            state.onBackGestureEnded()
        }
    }

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
            .then(
                if (state.isFullScreen) {
                    Modifier
                } else {
                    // 上部（ステータスバー）は BrowserToolBar の背景色で塗りつぶすため除外する。
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        // ime 表示時に Web 下部入力欄が隠れないよう GeckoView も IME 上に押し上げる。
                        // ただし resume 直後の resize で GPU プロセス kill が起きる経路があるため、
                        // setSession のタイミングを restoreSurfaceIfNeeded 側で「サイズが安定するまで
                        // 待つ」よう制御している。
                        .imePadding()
                }
            )
    ) {
        if (state.isFullScreen) {
            // フルスクリーン時はツールバー・翻訳バー・検索バーを非表示
        } else if (state.showFindInPage) {
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

    // 開発者ツールダイアログ
    if (state.showDevTools) {
        DevToolsDialog(
            focusedInput = state.devToolsFocusedInput,
            onCopyFocusedInputId = state::copyFocusedInputId,
            onRefresh = state::refreshDevToolsFocusedInput,
            onDismiss = state::closeDevTools,
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

private fun GeckoSession.logKey(): String = Integer.toHexString(System.identityHashCode(this))

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

package net.matsudamper.browser

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.cert.X509Certificate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/** タブの現在の接続のセキュリティ情報 (TLS) */
data class TabSecurityInfo(
    val isSecure: Boolean,
    val certificate: X509Certificate?,
)

@Stable
class BrowserTab(
    val tabId: String,
    val session: GeckoSession,
    val openerTabId: String?,
    currentUrl: String,
    sessionState: String,
    title: String,
    previewBitmap: ByteArray?,
    themeColor: Int? = null,
    private val onStateChanged: () -> Unit = {},
    private val onUrlChanged: (String, String) -> Unit = { _, _ -> },
    private val onSessionStateChanged: (String, String) -> Unit = { _, _ -> },
    private val onTitleChanged: (String, String) -> Unit = { _, _ -> },
    private val onPreviewBitmapChanged: (String, ByteArray?) -> Unit = { _, _ -> },
    private val onThemeColorChanged: (String, Int?) -> Unit = { _, _ -> },
) {
    private var currentUrlState by mutableStateOf(currentUrl)
    private var sessionStateState by mutableStateOf(sessionState)
    private var titleState by mutableStateOf(title)
    private var previewBitmapState: ByteArray? by mutableStateOf(previewBitmap)
    private var themeColorState: Int? by mutableStateOf(themeColor)

    var currentUrl: String
        get() = currentUrlState
        set(value) {
            if (currentUrlState == value) return
            currentUrlState = value
            onStateChanged()
            onUrlChanged(tabId, value)
        }

    var sessionState: String
        get() = sessionStateState
        set(value) {
            if (sessionStateState == value) return
            sessionStateState = value
            onStateChanged()
            onSessionStateChanged(tabId, value)
        }

    var title: String
        get() = titleState
        set(value) {
            if (titleState == value) return
            titleState = value
            onStateChanged()
            onTitleChanged(tabId, value)
        }

    var previewBitmap: ByteArray?
        get() = previewBitmapState
        set(value) {
            if (previewBitmapState.contentEqualsNullable(value)) return
            previewBitmapState = value
            onStateChanged()
            onPreviewBitmapChanged(tabId, value)
        }

    var themeColor: Int?
        get() = themeColorState
        set(value) {
            if (themeColorState == value) return
            themeColorState = value
            onStateChanged()
            onThemeColorChanged(tabId, value)
        }

    // ページのfavicon（ホーム追加時のアイコンに使用、永続化は不要）
    var faviconBitmap: Bitmap? by mutableStateOf(null)

    // 現在の接続のセキュリティ情報。サイトの設定画面での TLS 証明書確認に使用する（永続化は不要）
    var securityInfo: TabSecurityInfo? by mutableStateOf(null)

    // スクロール位置（永続化は不要）。BrowserTabScreenState がタブ切替で再生成されても
    // GeckoSession と同じ寿命を持つここで保持することで、復元タブのスクロール位置を維持する。
    // State 側にだけ持つと、復元直後は GeckoSession のスクロール位置が変化せず
    // onScrollChanged が発火しないため 0 のままとなり、PullToRefresh が誤発動する。
    var scrollY: Int by mutableIntStateOf(0)

    // 未オープンタブのセッション復元情報を保持
    internal var pendingSessionState: String? by mutableStateOf(null)

    // onNewSession 経由で作成されたタブの初回ナビゲーション完了までの目印。
    // 初回読み込みは GeckoView が session.open() 後に opener (window.open 元) の
    // コンテキスト付きで自動実行するため、アプリ側から loadUri してはいけない
    // (referrer / opener 連携が失われ Google Pay などのポップアップ決済が壊れる)。
    // restoreSession が自動読み込みを currentUrl の loadUri で上書きしないための
    // ガードとして使い、実 URL への onLocationChange 発火でクリアされる。
    internal var pendingInitialUrl: String? by mutableStateOf(null)

    // 初回読み込み時に referrer として送信する URL。コンテキストメニューの
    // 「新しいタブで開く」で、ホットリンク保護のあるサーバーが 403 を返さないように
    // 元ページの URL を引き継ぐために使用する。初回読み込みで消費される。
    internal var pendingReferrerUrl: String? = null

    // 初回ロードを GeckoView の Surface サイズ確定後まで遅延するための目印。
    // 未確定 viewport でロードすると ImageDocument の shrink-to-fit スケールが
    // 誤計算され、画像が小さく低解像度で表示されるのを回避する。
    // restoreSession で立ち、performInitialLoadIfPending で消費される。
    internal var pendingInitialLoad: Boolean = false

    // URL バーからの明示的なナビゲーション等で初回ロードの遅延実行をキャンセルする。
    // Surface サイズ確定後の performInitialLoadIfPending がホームページ URL で
    // 上書きすることを防ぐ。
    fun cancelPendingInitialLoad() {
        pendingInitialLoad = false
    }

    private val sessionDelegateHost = BrowserTabSessionDelegateHost(this)

    internal fun bindSessionDelegates() {
        sessionDelegateHost.bindToSession(session)
    }

    internal fun disposeSessionDelegates(cause: Throwable) {
        sessionDelegateHost.failPendingRequests(cause)
        sessionDelegateHost.detachUi()
    }

    /**
     * session.close() を about:blank へのナビゲーション完了後に遅延実行するよう登録する。
     * moz-extension:// ページを閉じる際に browser.storage.local の書き込みが完了するまで待つために使用する。
     */
    internal fun scheduleCloseOnBlankNavigation(action: () -> Unit) {
        sessionDelegateHost.scheduleCloseOnBlankNavigation(action)
    }

    fun attachSessionCallbacks(
        callbacks: BrowserSessionStateCallbacks,
        onOpenNewSessionRequest: (String) -> GeckoResult<GeckoSession>,
        onCloseRequest: (() -> Unit)? = null,
    ) {
        sessionDelegateHost.attachUi(
            callbacks = callbacks,
            onOpenNewSessionRequest = onOpenNewSessionRequest,
            onCloseRequest = onCloseRequest,
        )
    }

    fun detachSessionCallbacks() {
        sessionDelegateHost.detachUi()
    }

    /** SessionState から履歴キャッシュを初期化する */
    internal fun initHistoryFromSessionState(sessionState: GeckoSession.SessionState) {
        sessionDelegateHost.initHistoryCache(sessionState)
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    return when {
        this === other -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
}

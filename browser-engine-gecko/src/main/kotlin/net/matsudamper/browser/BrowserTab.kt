package net.matsudamper.browser

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

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

    // 未オープンタブのセッション復元情報を保持
    internal var pendingSessionState: String? by mutableStateOf(null)

    // onNewSession 経由で作成されたタブの初回ロード URL を保持。
    // GeckoView が session.open() を実行するため restoreSession では isOpen==true になるが、
    // GeckoView が target URL に自動遷移しないケースに備えて明示的に loadUri を呼ぶ。
    internal var pendingInitialUrl: String? by mutableStateOf(null)

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

package net.matsudamper.browser

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.matsudamper.browser.data.PersistedTabState
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
    private var suppressPersistence = false
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
            if (!suppressPersistence) {
                onUrlChanged(tabId, value)
            }
        }

    var sessionState: String
        get() = sessionStateState
        set(value) {
            if (sessionStateState == value) return
            sessionStateState = value
            onStateChanged()
            if (!suppressPersistence) {
                onSessionStateChanged(tabId, value)
            }
        }

    var title: String
        get() = titleState
        set(value) {
            if (titleState == value) return
            titleState = value
            onStateChanged()
            if (!suppressPersistence) {
                onTitleChanged(tabId, value)
            }
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
            if (!suppressPersistence) {
                onThemeColorChanged(tabId, value)
            }
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

    fun attachSessionCallbacks(
        callbacks: BrowserSessionStateCallbacks,
        onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
        onOpenNewSessionRequest: (String) -> GeckoResult<GeckoSession>,
        onCloseRequest: (() -> Unit)? = null,
    ) {
        sessionDelegateHost.attachUi(
            callbacks = callbacks,
            onDesktopNotificationPermissionRequest = onDesktopNotificationPermissionRequest,
            onOpenNewSessionRequest = onOpenNewSessionRequest,
            onCloseRequest = onCloseRequest,
        )
    }

    fun detachSessionCallbacks() {
        sessionDelegateHost.detachUi()
    }

    internal fun syncPersistedState(persistedTabState: PersistedTabState) {
        suppressPersistence = true
        try {
            currentUrl = persistedTabState.url
            sessionState = persistedTabState.sessionState
            title = persistedTabState.title.ifBlank { persistedTabState.url }
            themeColor = persistedTabState.themeColor
        } finally {
            suppressPersistence = false
        }
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    return when {
        this === other -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
}

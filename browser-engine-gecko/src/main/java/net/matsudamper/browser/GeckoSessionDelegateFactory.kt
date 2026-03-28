package net.matsudamper.browser

import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

interface BrowserSessionStateCallbacks {
    fun onCanGoBackChanged(value: Boolean)
    fun onCanGoForwardChanged(value: Boolean)
    fun onLoadError(uri: String?, error: WebRequestError)
    fun onLocationChange(url: String)
    fun onTitleChange(title: String)
    fun onContextMenu(element: GeckoSession.ContentDelegate.ContextElement)
    fun onRenderReady()
    fun onExternalResponse(response: WebResponse)
    fun onSessionStateChange(sessionState: GeckoSession.SessionState)
    fun onPageStart(url: String)
    fun onPageStop(success: Boolean)
    fun onTranslationStateChange(
        translationState: TranslationsController.SessionTranslation.TranslationState?,
    )
    fun onScrollChanged(scrollY: Int)
    fun onLoadRequest(
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny>?
}

data class GeckoSessionDelegateBundle(
    val permissionDelegate: GeckoSession.PermissionDelegate,
    val navigationDelegate: GeckoSession.NavigationDelegate,
    val contentDelegate: GeckoSession.ContentDelegate,
    val progressDelegate: GeckoSession.ProgressDelegate,
    val translationsDelegate: TranslationsController.SessionTranslation.Delegate,
    val scrollDelegate: GeckoSession.ScrollDelegate,
)

fun createGeckoSessionDelegateBundle(
    callbacks: BrowserSessionStateCallbacks,
    browserTab: BrowserTab,
    onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
    onOpenNewSessionRequest: (String) -> GeckoResult<GeckoSession>,
    onCloseRequest: (() -> Unit)? = null,
): GeckoSessionDelegateBundle {
    return GeckoSessionDelegateBundle(
        permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                Log.d(
                    "BrowserTabPermission",
                    "onContentPermissionRequest: permission=${perm.permission}, uri=${perm.uri}"
                )
                if (
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
                ) {
                    Log.d("BrowserTabPermission", "autoplay permission allowed")
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    )
                }
                if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    Log.d("BrowserTabPermission", "non-notification permission prompted")
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                    )
                }
                Log.d("BrowserTabPermission", "desktop notification delegated")
                return onDesktopNotificationPermissionRequest(perm.uri)
            }
        },
        navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                callbacks.onCanGoBackChanged(value)
            }

            override fun onCanGoForward(session: GeckoSession, value: Boolean) {
                callbacks.onCanGoForwardChanged(value)
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession> {
                return onOpenNewSessionRequest(uri)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                callbacks.onLoadError(uri, error)
                return null
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                return callbacks.onLoadRequest(request)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                // onNewSession 経由タブのナビゲーション完了時に pendingInitialUrl をクリア。
                // GeckoView は about:blank の後に実 URL を発火するため、実 URL の発火でクリアする。
                if (url != null && url != "about:blank") {
                    browserTab.pendingInitialUrl = null
                }
                callbacks.onLocationChange(url.orEmpty())
            }
        },
        contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                callbacks.onTitleChange(title.orEmpty())
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                callbacks.onContextMenu(element)
            }

            override fun onCloseRequest(session: GeckoSession) {
                onCloseRequest?.invoke()
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                callbacks.onRenderReady()
            }

            override fun onFirstComposite(session: GeckoSession) {
                callbacks.onRenderReady()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                callbacks.onExternalResponse(response)
            }
        },
        progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState,
            ) {
                callbacks.onSessionStateChange(sessionState)
            }

            override fun onPageStart(session: GeckoSession, url: String) {
                callbacks.onPageStart(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                callbacks.onPageStop(success)
            }
        },
        translationsDelegate = object : TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationsController.SessionTranslation.TranslationState?,
            ) {
                callbacks.onTranslationStateChange(translationState)
            }
        },
        scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(
                session: GeckoSession,
                scrollX: Int,
                scrollY: Int,
            ) {
                callbacks.onScrollChanged(scrollY)
            }
        },
    )
}

internal class BrowserTabSessionDelegateHost(
    private val browserTab: BrowserTab,
) {
    private val lock = Any()
    private var callbacks: BrowserSessionStateCallbacks? = null
    private var onDesktopNotificationPermissionRequest: ((String) -> GeckoResult<Int>)? = null
    private var onOpenNewSessionRequest: ((String) -> GeckoResult<GeckoSession>)? = null
    private var onCloseRequest: (() -> Unit)? = null
    private val pendingPermissionRequests = ArrayDeque<PendingPermissionRequest>()
    private val pendingNewSessionRequests = ArrayDeque<PendingNewSessionRequest>()

    // タブ切り替え時にUI側コールバックが再生成されるため、最新のナビゲーション状態をキャッシュして
    // attachUi 時にリプレイする
    private var cachedCanGoBack: Boolean = false
    private var cachedCanGoForward: Boolean = false

    private val delegateBundle = createGeckoSessionDelegateBundle(
        callbacks = object : BrowserSessionStateCallbacks {
            override fun onCanGoBackChanged(value: Boolean) {
                synchronized(lock) { cachedCanGoBack = value }
                currentCallbacks()?.onCanGoBackChanged(value)
            }

            override fun onCanGoForwardChanged(value: Boolean) {
                synchronized(lock) { cachedCanGoForward = value }
                currentCallbacks()?.onCanGoForwardChanged(value)
            }

            override fun onLoadError(uri: String?, error: WebRequestError) {
                currentCallbacks()?.onLoadError(uri, error)
            }

            override fun onLocationChange(url: String) {
                if (!(url == "about:blank" && browserTab.currentUrl != "about:blank")) {
                    browserTab.currentUrl = url
                }
                if (url.isNotBlank() && url != "about:blank") {
                    browserTab.pendingInitialUrl = null
                }
                currentCallbacks()?.onLocationChange(url)
            }

            override fun onTitleChange(title: String) {
                browserTab.title = title
                currentCallbacks()?.onTitleChange(title)
            }

            override fun onContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
                currentCallbacks()?.onContextMenu(element)
            }

            override fun onRenderReady() {
                currentCallbacks()?.onRenderReady()
            }

            override fun onExternalResponse(response: WebResponse) {
                currentCallbacks()?.onExternalResponse(response)
            }

            override fun onSessionStateChange(sessionState: GeckoSession.SessionState) {
                browserTab.sessionState = sessionState.toString().orEmpty()
                currentCallbacks()?.onSessionStateChange(sessionState)
            }

            override fun onPageStart(url: String) {
                currentCallbacks()?.onPageStart(url)
            }

            override fun onPageStop(success: Boolean) {
                currentCallbacks()?.onPageStop(success)
            }

            override fun onTranslationStateChange(
                translationState: TranslationsController.SessionTranslation.TranslationState?,
            ) {
                currentCallbacks()?.onTranslationStateChange(translationState)
            }

            override fun onScrollChanged(scrollY: Int) {
                currentCallbacks()?.onScrollChanged(scrollY)
            }

            override fun onLoadRequest(
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                return currentCallbacks()?.onLoadRequest(request)
            }
        },
        browserTab = browserTab,
        onDesktopNotificationPermissionRequest = { uri ->
            resolveDesktopNotificationPermission(uri)
        },
        onOpenNewSessionRequest = { uri ->
            resolveNewSession(uri)
        },
        onCloseRequest = {
            synchronized(lock) {
                onCloseRequest
            }?.invoke()
        },
    )

    fun bindToSession(session: GeckoSession) {
        session.permissionDelegate = delegateBundle.permissionDelegate
        session.navigationDelegate = delegateBundle.navigationDelegate
        session.contentDelegate = delegateBundle.contentDelegate
        session.progressDelegate = delegateBundle.progressDelegate
        session.translationsSessionDelegate = delegateBundle.translationsDelegate
        session.scrollDelegate = delegateBundle.scrollDelegate
    }

    fun attachUi(
        callbacks: BrowserSessionStateCallbacks,
        onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
        onOpenNewSessionRequest: (String) -> GeckoResult<GeckoSession>,
        onCloseRequest: (() -> Unit)? = null,
    ) {
        val canGoBack: Boolean
        val canGoForward: Boolean
        synchronized(lock) {
            this.callbacks = callbacks
            this.onDesktopNotificationPermissionRequest = onDesktopNotificationPermissionRequest
            this.onOpenNewSessionRequest = onOpenNewSessionRequest
            this.onCloseRequest = onCloseRequest
            canGoBack = cachedCanGoBack
            canGoForward = cachedCanGoForward
        }
        // GeckoSession はナビゲーション状態が変わらない限り onCanGoBack/onCanGoForward を再発火しないため、
        // キャッシュ済みの値をリプレイして UI 側の状態を同期する
        callbacks.onCanGoBackChanged(canGoBack)
        callbacks.onCanGoForwardChanged(canGoForward)
        flushPendingRequests()
    }

    fun detachUi() {
        synchronized(lock) {
            callbacks = null
            onDesktopNotificationPermissionRequest = null
            onOpenNewSessionRequest = null
            onCloseRequest = null
        }
    }

    fun failPendingRequests(cause: Throwable) {
        val permissions: List<PendingPermissionRequest>
        val newSessions: List<PendingNewSessionRequest>
        synchronized(lock) {
            permissions = pendingPermissionRequests.toList()
            newSessions = pendingNewSessionRequests.toList()
            pendingPermissionRequests.clear()
            pendingNewSessionRequests.clear()
        }
        permissions.forEach { pending ->
            pending.result.completeExceptionally(cause)
        }
        newSessions.forEach { pending ->
            pending.result.completeExceptionally(cause)
        }
    }

    private fun resolveDesktopNotificationPermission(uri: String): GeckoResult<Int> {
        val handler = synchronized(lock) {
            onDesktopNotificationPermissionRequest
        }
        if (handler != null) {
            return runCatching {
                handler(uri)
            }.getOrElse { error ->
                GeckoResult.fromException(error)
            }
        }
        return GeckoResult<Int>().also { result ->
            synchronized(lock) {
                pendingPermissionRequests.addLast(PendingPermissionRequest(uri, result))
            }
        }
    }

    private fun resolveNewSession(uri: String): GeckoResult<GeckoSession> {
        val handler = synchronized(lock) {
            onOpenNewSessionRequest
        }
        if (handler != null) {
            return runCatching {
                handler(uri)
            }.getOrElse { error ->
                GeckoResult.fromException(error)
            }
        }
        return GeckoResult<GeckoSession>().also { result ->
            synchronized(lock) {
                pendingNewSessionRequests.addLast(PendingNewSessionRequest(uri, result))
            }
        }
    }

    private fun flushPendingRequests() {
        flushPendingPermissionRequests()
        flushPendingNewSessionRequests()
    }

    private fun flushPendingPermissionRequests() {
        while (true) {
            val current = synchronized(lock) {
                val handler = onDesktopNotificationPermissionRequest
                val pending = pendingPermissionRequests.removeFirstOrNull()
                if (handler == null || pending == null) null else handler to pending
            } ?: break
            val (handler, pending) = current
            val upstream = runCatching {
                handler(pending.uri)
            }.getOrElse { error ->
                pending.result.completeExceptionally(error)
                continue
            }
            bridgeResult(upstream, pending.result)
        }
    }

    private fun flushPendingNewSessionRequests() {
        while (true) {
            val current = synchronized(lock) {
                val handler = onOpenNewSessionRequest
                val pending = pendingNewSessionRequests.removeFirstOrNull()
                if (handler == null || pending == null) null else handler to pending
            } ?: break
            val (handler, pending) = current
            val upstream = runCatching {
                handler(pending.uri)
            }.getOrElse { error ->
                pending.result.completeExceptionally(error)
                continue
            }
            bridgeResult(upstream, pending.result)
        }
    }

    private fun currentCallbacks(): BrowserSessionStateCallbacks? {
        return synchronized(lock) {
            callbacks
        }
    }

    private fun <T> bridgeResult(
        source: GeckoResult<T>,
        target: GeckoResult<T>,
    ) {
        source.accept(
            { value ->
                target.complete(value)
            },
            { throwable ->
                target.completeExceptionally(
                    throwable ?: IllegalStateException("GeckoResult が null 例外で失敗しました")
                )
            },
        )
    }

    private data class PendingPermissionRequest(
        val uri: String,
        val result: GeckoResult<Int>,
    )

    private data class PendingNewSessionRequest(
        val uri: String,
        val result: GeckoResult<GeckoSession>,
    )
}

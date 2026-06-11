package net.matsudamper.browser

import android.util.Log
import org.json.JSONObject
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
    fun onPreviewCaptureReady()
    fun onExternalResponse(response: WebResponse)
    fun onSessionStateChange(sessionState: GeckoSession.SessionState)
    fun onPageStart(url: String)
    fun onPageStop(success: Boolean)
    fun onWebAppManifest(manifest: JSONObject)
    fun onTranslationStateChange(
        translationState: TranslationsController.SessionTranslation.TranslationState?,
    )
    fun onScrollChanged(scrollY: Int)
    fun onLoadRequest(
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny>?
    fun onHistoryStateChange(items: List<HistoryStateItem>, currentIndex: Int)
    fun onAndroidPermissionsRequest(
        permissions: Array<String>?,
        onGrant: () -> Unit,
        onReject: () -> Unit,
    )
    fun onMediaPermissionRequest(
        uri: String,
        hasVideo: Boolean,
        hasAudio: Boolean,
        onResult: (grantVideo: Boolean, grantAudio: Boolean) -> Unit,
    )
    fun onGeolocationPermissionRequest(
        uri: String?,
        onResult: (allow: Boolean) -> Unit,
    )
}

/** タブ内ナビゲーション履歴の項目 */
data class HistoryStateItem(val uri: String, val title: String)

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
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                    // モック/拒否はコンテンツスクリプトが処理する。Gecko 本体の位置情報へ
                    // 到達するのは「実際の位置情報」設定時のみ許可する
                    Log.d("BrowserTabPermission", "geolocation permission delegated to site settings")
                    val result = GeckoResult<Int>()
                    callbacks.onGeolocationPermissionRequest(perm.uri) { allow ->
                        result.complete(
                            if (allow) {
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                            } else {
                                // DENY は Gecko に永続化され、後で「実際の位置情報」へ変更しても
                                // このデリゲートが呼ばれなくなるため、永続化されない PROMPT で拒否する
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                            },
                        )
                    }
                    return result
                }
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    Log.d("BrowserTabPermission", "desktop notification denied")
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
                Log.d("BrowserTabPermission", "non-notification permission prompted")
                return GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                )
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                Log.d(
                    "BrowserTabPermission",
                    "onAndroidPermissionsRequest: permissions=${permissions?.toList()}"
                )
                @Suppress("UNCHECKED_CAST")
                callbacks.onAndroidPermissionsRequest(
                    permissions = permissions as Array<String>?,
                    onGrant = { callback.grant() },
                    onReject = { callback.reject() },
                )
            }

            // getUserMedia のデバイス選択。デフォルト実装は reject するため、
            // 未実装だと Android パーミッションを許可してもマイク・カメラが拒否される。
            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                Log.d(
                    "BrowserTabPermission",
                    "onMediaPermissionRequest: uri=$uri, " +
                        "video=${video?.map { it.name }}, audio=${audio?.map { it.name }}"
                )
                val videoSource = video?.firstOrNull()
                val audioSource = audio?.firstOrNull()
                if (videoSource == null && audioSource == null) {
                    callback.reject()
                    return
                }
                // マイクの可否はサイトごとの設定に基づいて UI 層で判断する
                callbacks.onMediaPermissionRequest(
                    uri = uri,
                    hasVideo = videoSource != null,
                    hasAudio = audioSource != null,
                    onResult = { grantVideo, grantAudio ->
                        val grantedVideo = videoSource.takeIf { grantVideo }
                        val grantedAudio = audioSource.takeIf { grantAudio }
                        if (grantedVideo == null && grantedAudio == null) {
                            callback.reject()
                        } else {
                            callback.grant(grantedVideo, grantedAudio)
                        }
                    },
                )
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
                // javascript: URI は injectViewportZoom の loadUri 呼び出しが発火するため無視する。
                if (url?.startsWith("javascript:") == true) return
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
                callbacks.onPreviewCaptureReady()
            }

            override fun onFirstComposite(session: GeckoSession) {
                callbacks.onRenderReady()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                callbacks.onExternalResponse(response)
            }

            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
                callbacks.onWebAppManifest(manifest)
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
                // 新しいページでは前ページの証明書情報を持ち越さない
                browserTab.securityInfo = null
                callbacks.onPageStart(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                callbacks.onPageStop(success)
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
            ) {
                browserTab.securityInfo = TabSecurityInfo(
                    isSecure = securityInfo.isSecure,
                    certificate = securityInfo.certificate,
                )
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
    private var onOpenNewSessionRequest: ((String) -> GeckoResult<GeckoSession>)? = null
    private var onCloseRequest: (() -> Unit)? = null
    private val pendingNewSessionRequests = ArrayDeque<PendingNewSessionRequest>()

    // about:blank ナビゲーション完了時に実行するクローズ処理
    // disposeSessionDelegates 後に session.close() を遅延させるために使用
    private var pendingCloseAction: (() -> Unit)? = null

    fun scheduleCloseOnBlankNavigation(action: () -> Unit) {
        synchronized(lock) {
            pendingCloseAction = action
        }
    }

    // タブ切り替え時にUI側コールバックが再生成されるため、最新のナビゲーション状態をキャッシュして
    // attachUi 時にリプレイする
    private var cachedCanGoBack: Boolean = false
    private var cachedCanGoForward: Boolean = false
    private var cachedHistoryItems: List<HistoryStateItem> = emptyList()
    private var cachedHistoryCurrentIndex: Int = -1
    // UI未接続中に届いた manifest を失わないようにキャッシュする。
    // onPageStart でクリアし、attachUi 時にリプレイする。
    private var cachedWebAppManifest: JSONObject? = null

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
                if (url.startsWith("javascript:")) return
                if (!(url == "about:blank" && browserTab.currentUrl != "about:blank")) {
                    browserTab.currentUrl = url
                }
                if (url.isNotBlank() && url != "about:blank") {
                    browserTab.pendingInitialUrl = null
                }
                // about:blank へのナビゲーション完了時にクローズ処理を実行する
                // (拡張機能の設定ページを閉じる際に browser.storage.local 書き込みを待つため)
                if (url == "about:blank") {
                    val action = synchronized(lock) {
                        pendingCloseAction.also { pendingCloseAction = null }
                    }
                    action?.invoke()
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

            override fun onPreviewCaptureReady() {
                currentCallbacks()?.onPreviewCaptureReady()
            }

            override fun onExternalResponse(response: WebResponse) {
                currentCallbacks()?.onExternalResponse(response)
            }

            override fun onSessionStateChange(sessionState: GeckoSession.SessionState) {
                browserTab.sessionState = sessionState.toString().orEmpty()
                currentCallbacks()?.onSessionStateChange(sessionState)
            }

            override fun onPageStart(url: String) {
                // 新しいページでは前ページの manifest を持ち越さない
                synchronized(lock) { cachedWebAppManifest = null }
                currentCallbacks()?.onPageStart(url)
            }

            override fun onPageStop(success: Boolean) {
                currentCallbacks()?.onPageStop(success)
            }

            override fun onWebAppManifest(manifest: JSONObject) {
                synchronized(lock) { cachedWebAppManifest = manifest }
                currentCallbacks()?.onWebAppManifest(manifest)
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

            override fun onHistoryStateChange(items: List<HistoryStateItem>, currentIndex: Int) {
                // bindToSession で設定する historyDelegate 経由で呼ばれるため、ここでは何もしない
            }

            override fun onAndroidPermissionsRequest(
                permissions: Array<String>?,
                onGrant: () -> Unit,
                onReject: () -> Unit,
            ) {
                val cb = currentCallbacks()
                if (cb != null) {
                    cb.onAndroidPermissionsRequest(permissions, onGrant, onReject)
                } else {
                    onReject()
                }
            }

            override fun onMediaPermissionRequest(
                uri: String,
                hasVideo: Boolean,
                hasAudio: Boolean,
                onResult: (grantVideo: Boolean, grantAudio: Boolean) -> Unit,
            ) {
                val cb = currentCallbacks()
                if (cb != null) {
                    cb.onMediaPermissionRequest(uri, hasVideo, hasAudio, onResult)
                } else {
                    onResult(false, false)
                }
            }

            override fun onGeolocationPermissionRequest(
                uri: String?,
                onResult: (allow: Boolean) -> Unit,
            ) {
                val cb = currentCallbacks()
                if (cb != null) {
                    cb.onGeolocationPermissionRequest(uri, onResult)
                } else {
                    onResult(false)
                }
            }
        },
        browserTab = browserTab,
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
        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(
                session: GeckoSession,
                historyList: GeckoSession.HistoryDelegate.HistoryList,
            ) {
                val items = historyList.map { item ->
                    HistoryStateItem(
                        uri = item.uri.orEmpty(),
                        title = item.title.orEmpty(),
                    )
                }
                val currentIndex = historyList.currentIndex
                synchronized(lock) {
                    cachedHistoryItems = items
                    cachedHistoryCurrentIndex = currentIndex
                }
                currentCallbacks()?.onHistoryStateChange(items, currentIndex)
            }
        }
    }

    fun attachUi(
        callbacks: BrowserSessionStateCallbacks,
        onOpenNewSessionRequest: (String) -> GeckoResult<GeckoSession>,
        onCloseRequest: (() -> Unit)? = null,
    ) {
        val canGoBack: Boolean
        val canGoForward: Boolean
        val historyItems: List<HistoryStateItem>
        val historyCurrentIndex: Int
        val webAppManifest: JSONObject?
        synchronized(lock) {
            this.callbacks = callbacks
            this.onOpenNewSessionRequest = onOpenNewSessionRequest
            this.onCloseRequest = onCloseRequest
            canGoBack = cachedCanGoBack
            canGoForward = cachedCanGoForward
            historyItems = cachedHistoryItems
            historyCurrentIndex = cachedHistoryCurrentIndex
            webAppManifest = cachedWebAppManifest
        }
        // GeckoSession はナビゲーション状態が変わらない限り onCanGoBack/onCanGoForward を再発火しないため、
        // キャッシュ済みの値をリプレイして UI 側の状態を同期する
        callbacks.onCanGoBackChanged(canGoBack)
        callbacks.onCanGoForwardChanged(canGoForward)
        // タブ内ナビゲーション履歴も同様にリプレイする
        if (historyItems.isNotEmpty()) {
            callbacks.onHistoryStateChange(historyItems, historyCurrentIndex)
        }
        // UI未接続中に届いた manifest もリプレイして Add to Home で利用できるようにする
        if (webAppManifest != null) {
            callbacks.onWebAppManifest(webAppManifest)
        }
        flushPendingRequests()
    }

    /** SessionState から履歴キャッシュを初期化する（セッション復元時に呼ぶ） */
    fun initHistoryCache(sessionState: GeckoSession.SessionState) {
        val items = sessionState.map { item ->
            HistoryStateItem(uri = item.uri.orEmpty(), title = item.title.orEmpty())
        }
        val currentIndex = sessionState.currentIndex
        synchronized(lock) {
            cachedHistoryItems = items
            cachedHistoryCurrentIndex = currentIndex
        }
        currentCallbacks()?.onHistoryStateChange(items, currentIndex)
    }

    fun detachUi() {
        synchronized(lock) {
            callbacks = null
            onOpenNewSessionRequest = null
            onCloseRequest = null
        }
    }

    fun failPendingRequests(cause: Throwable) {
        val newSessions: List<PendingNewSessionRequest>
        synchronized(lock) {
            newSessions = pendingNewSessionRequests.toList()
            pendingNewSessionRequests.clear()
        }
        newSessions.forEach { pending ->
            pending.result.completeExceptionally(cause)
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
        flushPendingNewSessionRequests()
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

    private data class PendingNewSessionRequest(
        val uri: String,
        val result: GeckoResult<GeckoSession>,
    )
}

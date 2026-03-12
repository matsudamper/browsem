package net.matsudamper.browser

import android.util.Log
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
    onDesktopNotificationPermissionRequest: (String) -> GeckoResult<Int>,
    onOpenNewSessionRequest: (String) -> GeckoSession,
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
                return GeckoResult.fromValue(onOpenNewSessionRequest(uri))
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                callbacks.onLoadError(uri, error)
                return null
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
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

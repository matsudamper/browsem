package net.matsudamper.browser

import android.content.Context
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import org.json.JSONObject

/**
 * 決済ポップアップ専用の軽量コールバック。
 * opener タブの UI 状態には影響を与えず、外部アプリ起動だけを抑止する。
 */
internal class PaymentPopupSessionCallbacks(
    private val context: Context,
) : BrowserSessionStateCallbacks {
    override fun onCanGoBackChanged(value: Boolean) = Unit

    override fun onCanGoForwardChanged(value: Boolean) = Unit

    override fun onLoadError(uri: String?, error: WebRequestError) = Unit

    override fun onLocationChange(url: String) = Unit

    override fun onTitleChange(title: String) = Unit

    override fun onContextMenu(element: GeckoSession.ContentDelegate.ContextElement) = Unit

    override fun onRenderReady() = Unit

    override fun onPreviewCaptureReady() = Unit

    override fun onExternalResponse(response: WebResponse) = Unit

    override fun onSessionStateChange(sessionState: GeckoSession.SessionState) = Unit

    override fun onPageStart(url: String) = Unit

    override fun onPageStop(success: Boolean) = Unit

    override fun onWebAppManifest(manifest: JSONObject) = Unit

    override fun onTranslationStateChange(
        translationState: TranslationsController.SessionTranslation.TranslationState?,
    ) = Unit

    override fun onScrollChanged(scrollY: Int) = Unit

    override fun onSessionClosedUnexpectedly() = Unit

    override fun onLoadRequest(
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny>? {
        return when (val action = resolveExternalAppNavigationAction(context, request.uri)) {
            ExternalAppNavigationAction.AllowInBrowser -> null
            ExternalAppNavigationAction.AppNotFound -> {
                Toast.makeText(context, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show()
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            is ExternalAppNavigationAction.Launch,
            is ExternalAppNavigationAction.OpenFallback,
            -> GeckoResult.fromValue(AllowOrDeny.DENY)
        }
    }

    override fun onHistoryStateChange(items: List<HistoryStateItem>, currentIndex: Int) = Unit

    override fun onAndroidPermissionsRequest(
        permissions: Array<String>?,
        onGrant: () -> Unit,
        onReject: () -> Unit,
    ) {
        onReject()
    }

    override fun onMediaPermissionRequest(
        uri: String,
        hasVideo: Boolean,
        hasAudio: Boolean,
        onResult: (grantVideo: Boolean, grantAudio: Boolean) -> Unit,
    ) {
        onResult(false, false)
    }

    override fun onGeolocationPermissionRequest(
        uri: String?,
        onResult: (allow: Boolean) -> Unit,
    ) {
        onResult(false)
    }

    override fun onAutoplayPermissionRequest(
        uri: String?,
        onResult: (allow: Boolean) -> Unit,
    ) {
        onResult(false)
    }

    override fun onFullScreen(fullScreen: Boolean) = Unit
}

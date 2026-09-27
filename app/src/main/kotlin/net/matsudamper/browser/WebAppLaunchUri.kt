package net.matsudamper.browser

import android.net.Uri
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.WebAppId

/**
 * ホームに追加したアプリを起動する Intent の data。
 *
 * documentLaunchMode="intoExisting" は component と data URI で既存タスクを照合するため、
 * data にウェブアプリ ID を含めてアプリごとにタスクを分ける。
 * 起動ページやプロファイルはウェブアプリ ID から DB を引いて解決する。
 */
internal object WebAppLaunchUri {
    private const val SCHEME = "browsem-webapp"

    // authority が無いと文字列からの再パースで opaque URI になりクエリを読めないため、固定の authority を置く
    private const val WEB_APP_AUTHORITY = "app"
    private const val QUERY_WEB_APP_ID = "id"
    private const val QUERY_PAGE_URL = "url"

    fun create(webAppId: WebAppId): Uri {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(WEB_APP_AUTHORITY)
            .appendQueryParameter(QUERY_WEB_APP_ID, webAppId.value)
            .build()
    }

    /**
     * DB 管理導入前に追加されたアプリは data にページ URL とプロファイルを直接持つため、[WebAppLaunchTarget.Legacy] として扱う。
     * さらにプロファイル導入前のアプリはページ URL をそのまま data に持つため、デフォルトプロファイルとして扱う。
     */
    fun parse(uri: Uri?): WebAppLaunchTarget? {
        if (uri == null) return null
        if (uri.scheme != SCHEME) {
            return WebAppLaunchTarget.Legacy(
                launchUri = uri.toString(),
                pageUrl = uri.toString(),
                profileId = ProfileId.DEFAULT,
            )
        }
        val webAppIdValue = uri.getQueryParameter(QUERY_WEB_APP_ID)
        if (!webAppIdValue.isNullOrEmpty()) {
            return WebAppLaunchTarget.Registered(WebAppId(webAppIdValue))
        }
        val profileIdValue = uri.authority
        return WebAppLaunchTarget.Legacy(
            launchUri = uri.toString(),
            pageUrl = uri.getQueryParameter(QUERY_PAGE_URL),
            profileId = if (profileIdValue.isNullOrEmpty()) ProfileId.DEFAULT else ProfileId(profileIdValue),
        )
    }
}

internal sealed interface WebAppLaunchTarget {
    data class Registered(val webAppId: WebAppId) : WebAppLaunchTarget

    /**
     * @param launchUri 起動時に DB へ移行するため、ピン留めショートカットの照合に使う元の URI
     */
    data class Legacy(
        val launchUri: String,
        val pageUrl: String?,
        val profileId: ProfileId,
    ) : WebAppLaunchTarget
}

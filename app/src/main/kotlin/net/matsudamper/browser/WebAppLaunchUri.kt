package net.matsudamper.browser

import android.net.Uri
import net.matsudamper.browser.data.ProfileId

/**
 * ホームに追加したアプリを起動する Intent の data。
 *
 * documentLaunchMode="intoExisting" は component と data URI で既存タスクを照合するため、
 * ページ URL をそのまま data にすると別プロファイルで追加した同じ URL のアプリが同じタスクを再利用してしまう。
 * data にプロファイルを含めてタスクを分け、ページ URL はクエリに持たせる。
 */
internal object WebAppLaunchUri {
    private const val SCHEME = "browsem-webapp"
    private const val QUERY_PAGE_URL = "url"

    fun create(pageUrl: String, profileId: ProfileId): Uri {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(profileId.value)
            .appendQueryParameter(QUERY_PAGE_URL, pageUrl)
            .build()
    }

    /**
     * プロファイル導入前に追加されたアプリはページ URL をそのまま data に持つため、デフォルトプロファイルとして扱う。
     */
    fun parse(uri: Uri?): WebAppLaunchTarget? {
        if (uri == null) return null
        if (uri.scheme != SCHEME) {
            return WebAppLaunchTarget(pageUrl = uri.toString(), profileId = ProfileId.DEFAULT)
        }
        val profileIdValue = uri.authority
        return WebAppLaunchTarget(
            pageUrl = uri.getQueryParameter(QUERY_PAGE_URL),
            profileId = if (profileIdValue.isNullOrEmpty()) ProfileId.DEFAULT else ProfileId(profileIdValue),
        )
    }
}

internal data class WebAppLaunchTarget(
    val pageUrl: String?,
    val profileId: ProfileId,
)

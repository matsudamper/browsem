package net.matsudamper.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.WebAppId

/**
 * ホームに追加したアプリを起動する Intent の data。
 *
 * documentLaunchMode="intoExisting" は component と data URI で既存タスクを照合するため、
 * data にウェブアプリ ID を含めてアプリごとにタスクを分ける。
 * 起動ページやプロファイルはウェブアプリ ID から DB を引いて解決する。
 *
 * 古いバックアップを復元すると、その後に追加したアプリの登録が DB から消えてもホームのアイコンは残る。
 * そのときに登録を作り直せるよう、起動ページとプロファイルも data に持たせる。
 */
internal object WebAppLaunchUri {
    private const val SCHEME = "browsem-webapp"

    // authority が無いと文字列からの再パースで opaque URI になりクエリを読めないため、固定の authority を置く
    private const val WEB_APP_AUTHORITY = "app"
    private const val QUERY_WEB_APP_ID = "id"
    private const val QUERY_PAGE_URL = "url"
    private const val QUERY_PROFILE_ID = "profile"

    // 独立した Recents エントリは WebAppActivity の documentLaunchMode="intoExisting"
    // (= FLAG_ACTIVITY_NEW_DOCUMENT 相当) が保証するため、Intent 側にフラグは不要。
    fun createIntent(context: Context, launchInfo: WebAppLaunchInfo): Intent {
        return Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = create(launchInfo)
        }
    }

    private fun create(launchInfo: WebAppLaunchInfo): Uri {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(WEB_APP_AUTHORITY)
            .appendQueryParameter(QUERY_WEB_APP_ID, launchInfo.webAppId.value)
            .appendQueryParameter(QUERY_PAGE_URL, launchInfo.startUrl)
            .appendQueryParameter(QUERY_PROFILE_ID, launchInfo.profileId.value)
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
            val profileIdValue = uri.getQueryParameter(QUERY_PROFILE_ID)
            return WebAppLaunchTarget.Registered(
                webAppId = WebAppId(webAppIdValue),
                launchUri = uri.toString(),
                pageUrl = uri.getQueryParameter(QUERY_PAGE_URL),
                profileId = if (profileIdValue.isNullOrEmpty()) ProfileId.DEFAULT else ProfileId(profileIdValue),
            )
        }
        val profileIdValue = uri.authority
        return WebAppLaunchTarget.Legacy(
            launchUri = uri.toString(),
            pageUrl = uri.getQueryParameter(QUERY_PAGE_URL),
            profileId = if (profileIdValue.isNullOrEmpty()) ProfileId.DEFAULT else ProfileId(profileIdValue),
        )
    }
}

internal data class WebAppLaunchInfo(
    val webAppId: WebAppId,
    val startUrl: String,
    val profileId: ProfileId,
)

internal sealed interface WebAppLaunchTarget {
    /**
     * @param launchUri 登録を作り直すとき、ピン留めショートカットの照合に使う元の URI
     * @param pageUrl 登録が DB に無いときに作り直すための起動ページ。DB の登録がある場合はそちらを使う
     * @param profileId 登録が DB に無いときに作り直すためのプロファイル
     */
    data class Registered(
        val webAppId: WebAppId,
        val launchUri: String,
        val pageUrl: String?,
        val profileId: ProfileId,
    ) : WebAppLaunchTarget

    /**
     * @param launchUri 起動時に DB へ移行するため、ピン留めショートカットの照合に使う元の URI
     */
    data class Legacy(
        val launchUri: String,
        val pageUrl: String?,
        val profileId: ProfileId,
    ) : WebAppLaunchTarget
}

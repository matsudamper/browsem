package net.matsudamper.browser

import android.app.Activity
import android.content.Intent
import org.mozilla.geckoview.GeckoSession

/**
 * `window.open` の要求をカスタムタブとして開く。
 *
 * 未 open の [GeckoSession] を Gecko へ返し、同じセッションを [WindowOpenHandoffStore] 経由で
 * [CustomTabActivity] へ渡す。セッションを開いて読み込むのは Gecko 側の役目で、opener 関係も
 * そこで張られるため、アプリ側から open や loadUri をしてはいけない。
 *
 * Activity の起動を次のメッセージへ回すのは、コールバック内で opener の GeckoView が
 * 外れるのを避けるため。詳細は [WindowOpenSessionPolicy] を参照。
 *
 * 引き渡した子は opener のタブ一覧に並ばず、タブ一覧の変化も起きない。opener が背面へ回る前に
 * 保持へ切り替えるため、ここで明示的に再判定する。
 */
internal fun Activity.openWindowOpenRequestInCustomTab(
    uri: String,
    openerTabId: String,
    browserTabController: BrowserTabController,
    browserSessionLifecycleController: BrowserSessionLifecycleController,
): GeckoSession {
    val session = GeckoSession()
    val token = WindowOpenHandoffStore.store(
        session = session,
        initialUrl = uri,
        openerTabId = openerTabId,
    )
    browserSessionLifecycleController.retainOpenersOfLivePopups(
        tabs = browserTabController.tabs,
        selectedTabId = browserTabController.selectedTabId,
    )
    WindowOpenSessionPolicy.postToMain {
        startActivity(
            Intent(this, CustomTabActivity::class.java).apply {
                putExtra(WindowOpenHandoffStore.EXTRA_HANDOFF_TOKEN, token)
            },
        )
    }
    return session
}

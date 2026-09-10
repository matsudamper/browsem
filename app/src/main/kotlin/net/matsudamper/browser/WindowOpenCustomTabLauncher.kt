package net.matsudamper.browser

import android.app.Activity
import android.content.Intent
import java.util.UUID
import org.mozilla.geckoview.GeckoResult
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
    // 渡した先のタブ ID をここで決める。載る前にさらに window.open が来た場合、その子の
    // opener はこのセッションであって、こちらの opener ではない。
    val handedOffTabId = UUID.randomUUID().toString()
    val holdingDelegate = WindowOpenHandoffHoldingDelegate(
        onChainedWindowOpen = { chainedUri ->
            openWindowOpenRequestInCustomTab(
                uri = chainedUri,
                openerTabId = handedOffTabId,
                browserTabController = browserTabController,
                browserSessionLifecycleController = browserSessionLifecycleController,
            )
        },
    )
    holdingDelegate.bindTo(session)
    val token = WindowOpenHandoffStore.store(
        session = session,
        initialUrl = uri,
        tabId = handedOffTabId,
        openerTabId = openerTabId,
        holdingDelegate = holdingDelegate,
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

/**
 * セッションを Gecko へ返してから、起動先の画面がタブへ載せて本来の delegate を張るまでの間に
 * 届くイベントを受け止める。この間は delegate が無いため、ポップアップの自己終了や、さらに
 * ポップアップを開く要求が握り潰される。
 *
 * タブに載ると `bindToSession` が delegate を差し替えるので、役目はそこまで。
 */
internal class WindowOpenHandoffHoldingDelegate(
    private val onChainedWindowOpen: (String) -> GeckoSession,
) : GeckoSession.ContentDelegate, GeckoSession.NavigationDelegate {
    /** タブへ載る前に `window.close` が呼ばれたか。載せた直後に画面を閉じるために使う。 */
    var isCloseRequested: Boolean = false
        private set

    /**
     * タブへ載る前に遷移が済んだ場合の遷移先。過去の onLocationChange は新しい delegate へ
     * 再送されないため、ここで覚えておかないとタブの URL が要求時のまま食い違う。
     */
    var latestLocation: String? = null
        private set

    fun bindTo(session: GeckoSession) {
        session.contentDelegate = this
        session.navigationDelegate = this
    }

    override fun onCloseRequest(session: GeckoSession) {
        isCloseRequested = true
    }

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean,
    ) {
        if (url.isNullOrBlank() || url == "about:blank") return
        latestLocation = url
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
        return GeckoResult.fromValue(onChainedWindowOpen(uri))
    }
}

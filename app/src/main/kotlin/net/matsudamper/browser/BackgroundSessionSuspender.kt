package net.matsudamper.browser

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import net.matsudamper.browser.feature.media.MediaWebExtension
import org.mozilla.geckoview.GeckoSession

internal class BackgroundSessionHost(
    val tabs: () -> List<BrowserTab>,
    val lifecycleController: BrowserSessionLifecycleController,
)

/**
 * アプリが画面から外れている間、再生中でないタブの JS を止める。
 * 再生の継続と、通知以外の再生開始の拒否は [MediaWebExtension] が行う。
 */
internal class BackgroundSessionSuspender(
    private val mediaWebExtension: MediaWebExtension,
) : DefaultLifecycleObserver {
    private val hosts = mutableSetOf<BackgroundSessionHost>()
    private val suspendedByHost = mutableMapOf<BackgroundSessionHost, List<BrowserTab>>()
    private var inBackground = false

    fun track(host: BackgroundSessionHost) {
        hosts.add(host)
    }

    fun untrack(host: BackgroundSessionHost) {
        hosts.remove(host)
        suspendedByHost.remove(host)
    }

    /** 通知操作でセッションを起こしたとき、画面復帰の判定に active だと伝える。 */
    fun noteSessionActivated(session: GeckoSession) {
        hosts.forEach { host ->
            val tab = host.tabs().firstOrNull { it.session === session }
            if (tab != null) {
                tab.setSessionActive(true)
            }
        }
    }

    fun onPlaybackStopped(session: GeckoSession) {
        if (!inBackground) {
            return
        }
        hosts.forEach { host ->
            val tab = host.tabs().firstOrNull { it.session === session }
            if (tab == null) {
                return@forEach
            }
            val suspended = host.lifecycleController.suspendWhileHostStopped(
                tab = tab,
                isPlaying = false,
            )
            if (!suspended) {
                return@forEach
            }
            val current = suspendedByHost[host].orEmpty()
            if (tab !in current) {
                suspendedByHost[host] = current + tab
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        inBackground = true
        // 止める前に、鳴っている再生を継続扱いに残す。
        mediaWebExtension.onHostEnteredBackground()
        hosts.forEach { host ->
            val suspended = host.tabs().filter { tab ->
                host.lifecycleController.suspendWhileHostStopped(
                    tab = tab,
                    isPlaying = mediaWebExtension.isSessionPlaying(tab.session),
                )
            }
            if (suspended.isNotEmpty()) {
                suspendedByHost[host] = suspended
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        inBackground = false
        // タブを起こす前に判定を外す。戻したページの再生開始まで止めない。
        mediaWebExtension.onHostEnteredForeground()
        val pending = suspendedByHost.toMap()
        suspendedByHost.clear()
        pending.forEach { (host, tabs) ->
            if (host !in hosts) {
                return@forEach
            }
            tabs.forEach { tab ->
                host.lifecycleController.resumeSuspendedWhileHostStopped(tab)
            }
        }
    }
}

internal fun ViewModel.trackBackgroundSessions(
    suspender: BackgroundSessionSuspender,
    browserTabController: BrowserTabController,
    lifecycleController: BrowserSessionLifecycleController,
) {
    val host = BackgroundSessionHost(
        tabs = { browserTabController.tabs },
        lifecycleController = lifecycleController,
    )
    suspender.track(host)
    addCloseable { suspender.untrack(host) }
}

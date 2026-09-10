package net.matsudamper.browser

import androidx.annotation.VisibleForTesting
import org.mozilla.geckoview.GeckoSession

/**
 * `window.open` で開いたポップアップを別画面へ引き渡したときに、opener 側から見えなくなる
 * 子セッションを覚えておく登録簿。
 *
 * opener と子が同じ [BrowserTabController] に並んでいる間は tabs から辿れるが、子を別画面へ
 * 渡すと opener の一覧から消える。そのままでは opener がバックグラウンドで停止され、
 * `window.opener` 越しのやり取りが途切れる。
 *
 * 引き渡した直後は Gecko がセッションを open する前のため、一度でも open を確認するまでは
 * 生存扱いにする。open されないまま [UNOPENED_TIMEOUT_MS] を過ぎた登録は、引き渡し先の画面が
 * 起動しなかったとみなして捨てる。この時間は引き渡し自体の保持期間より短くしてはいけない。
 * 先に切れると、画面がセッションを受け取る前に opener の保持が解かれる。
 */
object HandedOffPopupRegistry {
    private const val UNOPENED_TIMEOUT_MS = 2 * 60 * 1000L

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    private class Entry(
        val openerTabId: String,
        val session: GeckoSession,
        val registeredAt: Long,
    ) {
        var hasBeenOpened: Boolean = false
    }

    fun register(openerTabId: String, session: GeckoSession) {
        synchronized(lock) {
            pruneLocked()
            entries += Entry(
                openerTabId = openerTabId,
                session = session,
                registeredAt = System.currentTimeMillis(),
            )
        }
    }

    fun unregister(session: GeckoSession) {
        synchronized(lock) {
            entries.removeAll { it.session === session }
        }
    }

    /** 引き渡した子が生きている opener のタブ ID。 */
    fun liveOpenerTabIds(): Set<String> {
        return synchronized(lock) {
            pruneLocked()
            entries.map { it.openerTabId }.toSet()
        }
    }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        entries.removeAll { entry ->
            if (entry.session.isOpen) {
                entry.hasBeenOpened = true
                return@removeAll false
            }
            entry.hasBeenOpened || now - entry.registeredAt > UNOPENED_TIMEOUT_MS
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

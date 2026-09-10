package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import java.util.UUID
import org.mozilla.geckoview.GeckoSession

/**
 * `window.open` のポップアップをカスタムタブとして開くために、GeckoSession を
 * 起動先の Activity へ渡すプロセス内受け渡しストア。
 *
 * `onNewSession` で返したセッションに対して Gecko が opener 関係を張るため、URL だけを渡して
 * 開き直すことはできない。GeckoSession は Intent に載せられないので、実体はこのストアが保持し、
 * Intent にはトークンのみを渡す。
 *
 * 引き渡しと同時に [HandedOffPopupRegistry] へ登録し、opener 側の画面が背面に回っても
 * JS が止まらないようにする。
 */
internal object WindowOpenHandoffStore {
    const val EXTRA_HANDOFF_TOKEN = "net.matsudamper.browser.extra.WINDOW_OPEN_HANDOFF_TOKEN"

    // 受け渡しは startActivity 直後に消費される想定だが、コールドスタートに備えて余裕を持たせる。
    // HandedOffPopupRegistry の未 open 保持期間と揃える（先に切れると opener の保持が解かれる）。
    private const val STALE_ENTRY_MS = 2 * 60 * 1000L

    // 取り出されないまま溜まり続けないよう、保持件数に上限を設ける
    private const val MAX_ENTRIES = 8

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    // 期限切れの掃除は store / consume でしか走らないため、消費されないまま次の
    // ポップアップも開かれないと、開いたセッションと暫定 delegate が捕まえている
    // 起動元一式がプロセス終了まで残る。登録時に掃除を予約しておく。
    private val staleEntryCleanupHandler = Handler(Looper.getMainLooper())

    private class Entry(
        val session: GeckoSession,
        val initialUrl: String,
        val tabId: String,
        val holdingDelegate: WindowOpenHandoffHoldingDelegate,
        val createdAt: Long,
    )

    class Handoff internal constructor(
        val session: GeckoSession,
        val initialUrl: String,
        val tabId: String,
        val holdingDelegate: WindowOpenHandoffHoldingDelegate,
    )

    /** 引き渡すセッションを登録し、Intent に載せるトークンを返す。 */
    fun store(
        session: GeckoSession,
        initialUrl: String,
        tabId: String,
        openerTabId: String,
        holdingDelegate: WindowOpenHandoffHoldingDelegate,
    ): String {
        val token = UUID.randomUUID().toString()
        val evicted = synchronized(lock) {
            val staleEntries = removeStaleLocked()
            val overflowEntry = if (entries.size >= MAX_ENTRIES) {
                entries.keys.firstOrNull()?.let { entries.remove(it) }
            } else {
                null
            }
            entries[token] = Entry(
                session = session,
                initialUrl = initialUrl,
                tabId = tabId,
                holdingDelegate = holdingDelegate,
                createdAt = System.currentTimeMillis(),
            )
            staleEntries + listOfNotNull(overflowEntry)
        }
        evicted.forEach { discard(it) }
        HandedOffPopupRegistry.register(openerTabId = openerTabId, session = session)
        scheduleStaleEntryCleanup()
        return token
    }

    private fun scheduleStaleEntryCleanup() {
        staleEntryCleanupHandler.postDelayed(
            {
                val staleEntries = synchronized(lock) { removeStaleLocked() }
                staleEntries.forEach { discard(it) }
            },
            STALE_ENTRY_MS,
        )
    }

    /** トークンに対応するセッションを取り出して削除する。存在しなければ null。 */
    fun consume(token: String): Handoff? {
        val (handoff, staleEntries) = synchronized(lock) {
            val staleEntries = removeStaleLocked()
            val entry = entries.remove(token)
            val handoff = entry?.let {
                Handoff(
                    session = it.session,
                    initialUrl = it.initialUrl,
                    tabId = it.tabId,
                    holdingDelegate = it.holdingDelegate,
                )
            }
            handoff to staleEntries
        }
        staleEntries.forEach { discard(it) }
        return handoff
    }

    private fun removeStaleLocked(): List<Entry> {
        val now = System.currentTimeMillis()
        val stale = entries.filterValues { now - it.createdAt > STALE_ENTRY_MS }
        stale.keys.forEach { entries.remove(it) }
        return stale.values.toList()
    }

    /** 引き取り手のないセッションは開いたままにせず閉じる。 */
    private fun discard(entry: Entry) {
        HandedOffPopupRegistry.unregister(entry.session)
        if (entry.session.isOpen) {
            entry.session.close()
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

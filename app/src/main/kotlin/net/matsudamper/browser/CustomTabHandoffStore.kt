package net.matsudamper.browser

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import java.util.UUID
import org.mozilla.geckoview.GeckoSession

/**
 * カスタムタブから通常ブラウザへ「ブラウザで開く」した際に、GeckoSession をそのまま
 * 引き継ぐためのプロセス内受け渡しストア。
 *
 * SessionState を渡して復元すると open→restoreState で読み込みが走るため、ワンタイム
 * トークンや POST 結果のページは開き直せず認証エラーになる。読み込みなしで同一の状態を
 * 引き継ぐには、開いたままのセッション自体を渡すしかない。
 *
 * GeckoSession は Intent に載せられないので、実体はこのストアが保持し、Intent には
 * トークンのみを渡す。複数のカスタムタブが同時に存在しても衝突しないよう、受け渡しごとに
 * 一意のトークンを発行する。
 */
object CustomTabHandoffStore {
    const val EXTRA_HANDOFF_TOKEN = "net.matsudamper.browser.extra.CUSTOM_TAB_HANDOFF_TOKEN"

    // 受け渡しは startActivity 直後に消費される想定だが、コールドスタートや構成変更に備えて余裕を持たせる
    private const val STALE_ENTRY_MS = 2 * 60 * 1000L

    // 取り出されないまま溜まり続けないよう、保持件数に上限を設ける
    private const val MAX_ENTRIES = 8

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    // 期限切れの掃除は store / consume でしか走らないため、消費されないまま次の受け渡しも
    // 発生しないと、開いたままのセッションがプロセス終了まで残る。登録時に掃除を予約しておく。
    private val staleEntryCleanupHandler = Handler(Looper.getMainLooper())

    private class Entry(
        val session: GeckoSession,
        val sessionState: String,
        val createdAt: Long,
        val onDiscard: (GeckoSession) -> Unit,
    )

    class Handoff internal constructor(
        val session: GeckoSession,
        /** セッションが閉じている（コンテンツプロセスの停止後など）ときに復元へ使う退避状態。 */
        val sessionState: String,
        private val onDiscard: (GeckoSession) -> Unit,
    ) {
        /** 取り出したセッションをタブへ載せずに捨てるときに呼ぶ。 */
        fun discardSession() {
            onDiscard(session)
            if (session.isOpen) {
                session.close()
            }
        }
    }

    /**
     * 引き継ぐセッションを登録し、Intent に載せるトークンを返す。
     *
     * [onDiscard] は引き取り手がないまま破棄するときに呼ぶ。引き渡し元で維持していた
     * 再生状態など、セッションに紐づく参照を手放すために使う。
     */
    fun store(
        session: GeckoSession,
        sessionState: String,
        onDiscard: (GeckoSession) -> Unit,
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
                sessionState = sessionState,
                createdAt = System.currentTimeMillis(),
                onDiscard = onDiscard,
            )
            staleEntries + listOfNotNull(overflowEntry)
        }
        evicted.forEach { discard(it) }
        scheduleStaleEntryCleanup()
        return token
    }

    /** トークンに対応するセッションを取り出して削除する。存在しなければ null。 */
    fun consume(token: String): Handoff? {
        val (handoff, staleEntries) = synchronized(lock) {
            val staleEntries = removeStaleLocked()
            val entry = entries.remove(token)
            val handoff = entry?.let {
                Handoff(
                    session = it.session,
                    sessionState = it.sessionState,
                    onDiscard = it.onDiscard,
                )
            }
            handoff to staleEntries
        }
        staleEntries.forEach { discard(it) }
        return handoff
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

    private fun removeStaleLocked(): List<Entry> {
        val now = System.currentTimeMillis()
        // 掃除は期限ちょうどに走る。ここを厳密な比較にすると取りこぼして残り続ける。
        val stale = entries.filterValues { now - it.createdAt >= STALE_ENTRY_MS }
        stale.keys.forEach { entries.remove(it) }
        return stale.values.toList()
    }

    /** 引き取り手のないセッションは開いたままにせず閉じる。 */
    private fun discard(entry: Entry) {
        entry.onDiscard(entry.session)
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

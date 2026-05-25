package net.matsudamper.browser

import androidx.annotation.VisibleForTesting
import java.util.UUID

/**
 * カスタムタブから通常ブラウザへ「ブラウザで開く」した際に、GeckoSession の状態
 * （履歴・スクロール位置など）を引き継ぐためのプロセス内受け渡しストア。
 *
 * SessionState 文字列を Intent extra に直接載せると Binder のトランザクションサイズ上限に
 * 当たり得るため、状態本体はこのストアに保持し、Intent ではトークンのみを渡す。
 *
 * 複数のカスタムタブが同時に存在しても衝突しないよう、受け渡しごとに一意のトークンを発行する。
 */
object CustomTabHandoffStore {
    const val EXTRA_HANDOFF_TOKEN = "net.matsudamper.browser.extra.CUSTOM_TAB_HANDOFF_TOKEN"

    // 受け渡しは startActivity 直後に消費される想定だが、コールドスタートや構成変更に備えて余裕を持たせる
    private const val STALE_ENTRY_MS = 2 * 60 * 1000L

    // 取り出されないまま溜まり続けないよう、保持件数に上限を設ける
    private const val MAX_ENTRIES = 8

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    private data class Entry(
        val sessionState: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    /** 引き継ぐ SessionState を登録し、Intent に載せるトークンを返す。 */
    fun store(sessionState: String): String {
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            cleanupLocked()
            if (entries.size >= MAX_ENTRIES) {
                entries.keys.firstOrNull()?.let { entries.remove(it) }
            }
            entries[token] = Entry(sessionState = sessionState)
        }
        return token
    }

    /** トークンに対応する SessionState を取り出して削除する。存在しなければ null。 */
    fun consume(token: String): String? {
        return synchronized(lock) {
            cleanupLocked()
            entries.remove(token)?.sessionState
        }
    }

    private fun cleanupLocked() {
        val now = System.currentTimeMillis()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().createdAt > STALE_ENTRY_MS) {
                iterator.remove()
            }
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

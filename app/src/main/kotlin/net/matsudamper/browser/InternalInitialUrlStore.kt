package net.matsudamper.browser

import androidx.annotation.VisibleForTesting
import java.util.UUID

/**
 * アプリ内から exported な Activity へ、外部に開かせたくない URL を渡すためのプロセス内ストア。
 *
 * moz-extension:// のような内部スキームを Intent の data に載せると、明示 Intent を投げられる
 * 他アプリからも同じ URL を開かせられてしまう。URL 本体はこのストアが保持し、Intent には
 * 推測できないトークンだけを渡す。
 */
object InternalInitialUrlStore {
    const val EXTRA_INITIAL_URL_TOKEN = "net.matsudamper.browser.extra.INTERNAL_INITIAL_URL_TOKEN"

    // 受け渡しは startActivity 直後に消費される想定だが、コールドスタートに備えて余裕を持たせる
    private const val STALE_ENTRY_MS = 2 * 60 * 1000L

    // 取り出されないまま溜まり続けないよう、保持件数に上限を設ける
    private const val MAX_ENTRIES = 8

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    private class Entry(
        val url: String,
        val createdAt: Long,
    )

    /** 渡す URL を登録し、Intent に載せるトークンを返す。 */
    fun store(url: String): String {
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            removeStaleLocked()
            if (entries.size >= MAX_ENTRIES) {
                entries.keys.firstOrNull()?.let { entries.remove(it) }
            }
            entries[token] = Entry(url = url, createdAt = System.currentTimeMillis())
        }
        return token
    }

    /** トークンに対応する URL を取り出して削除する。存在しなければ null。 */
    fun consume(token: String): String? {
        return synchronized(lock) {
            removeStaleLocked()
            entries.remove(token)?.url
        }
    }

    private fun removeStaleLocked() {
        val now = System.currentTimeMillis()
        // 掃除は期限ちょうどに走る。ここを厳密な比較にすると取りこぼして残り続ける。
        entries.values.removeAll { entry -> now - entry.createdAt >= STALE_ENTRY_MS }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

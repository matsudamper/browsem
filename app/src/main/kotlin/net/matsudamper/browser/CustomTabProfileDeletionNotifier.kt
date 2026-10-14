package net.matsudamper.browser

import net.matsudamper.browser.data.ProfileId

/**
 * 本体ブラウザでプロファイルを削除したとき、開いているカスタムタブへ伝える。
 * カスタムタブは別 Activity の BrowserTabController を持つため、本体の削除だけではセッションが残る。
 */
internal class CustomTabProfileDeletionNotifier {
    private val lock = Any()
    private val listeners = mutableListOf<suspend (ProfileId) -> Unit>()

    fun addListener(listener: suspend (ProfileId) -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: suspend (ProfileId) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    suspend fun notifyProfileDeleted(profileId: ProfileId) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener ->
            listener(profileId)
        }
    }
}

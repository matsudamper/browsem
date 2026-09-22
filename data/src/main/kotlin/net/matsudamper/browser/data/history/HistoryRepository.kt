package net.matsudamper.browser.data.history

import android.content.Context
import kotlinx.coroutines.flow.Flow
import net.matsudamper.browser.data.ProfileId

/** 閲覧履歴。記録・参照・削除はすべてプロファイル単位で行う */
class HistoryRepository(context: Context) {
    private val db = BrowserDatabase.getInstance(context)
    private val dao = db.historyDao()

    suspend fun recordVisit(profileId: ProfileId, url: String, title: String): Long {
        return dao.insert(
            HistoryEntry(
                url = url,
                title = title,
                visitedAt = System.currentTimeMillis(),
                profileId = profileId.value,
            ),
        )
    }

    suspend fun updateTitle(id: Long, title: String) {
        dao.updateTitle(id, title)
    }

    fun search(profileId: ProfileId, query: String, limit: Int = 50): Flow<List<HistoryEntry>> =
        dao.search(profileId.value, query, limit)

    fun getRecent(profileId: ProfileId, limit: Int = 100, offset: Int = 0): Flow<List<HistoryEntry>> =
        dao.getRecent(profileId.value, limit, offset)

    fun searchSuggestions(profileId: ProfileId, query: String, limit: Int = 8): Flow<List<HistoryEntry>> =
        dao.searchSuggestions(profileId.value, query, limit)

    fun getRecentSuggestions(profileId: ProfileId, limit: Int = 8): Flow<List<HistoryEntry>> =
        dao.getRecentSuggestions(profileId.value, limit)

    /** 指定プロファイルの履歴をすべて削除する */
    suspend fun deleteAll(profileId: ProfileId) = dao.deleteAllOfProfile(profileId.value)

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}

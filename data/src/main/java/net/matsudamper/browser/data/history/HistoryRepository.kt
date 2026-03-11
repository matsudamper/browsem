package net.matsudamper.browser.data.history

import android.content.Context
import kotlinx.coroutines.flow.Flow

class HistoryRepository(context: Context) {
    private val db = BrowserDatabase.getInstance(context)
    private val dao = db.historyDao()

    suspend fun recordVisit(url: String, title: String): Long {
        return dao.insert(
            HistoryEntry(
                url = url,
                title = title,
                visitedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateTitle(id: Long, title: String) {
        dao.updateTitle(id, title)
    }

    fun search(query: String, limit: Int = 50): Flow<List<HistoryEntry>> = dao.search(query, limit)

    fun getRecent(limit: Int = 100, offset: Int = 0): Flow<List<HistoryEntry>> =
        dao.getRecent(limit, offset)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}

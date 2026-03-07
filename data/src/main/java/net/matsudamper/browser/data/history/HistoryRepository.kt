package net.matsudamper.browser.data.history

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

class HistoryRepository(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        BrowserDatabase::class.java,
        "browser.db",
    ).build()
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

    fun search(query: String): Flow<List<HistoryEntry>> = dao.search(query)

    fun getRecent(limit: Int = 100, offset: Int = 0): Flow<List<HistoryEntry>> =
        dao.getRecent(limit, offset)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}

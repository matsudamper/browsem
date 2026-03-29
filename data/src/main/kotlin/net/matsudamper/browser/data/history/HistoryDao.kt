package net.matsudamper.browser.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Query("UPDATE history SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query(
        "SELECT * FROM history WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' " +
            "ORDER BY visitedAt DESC LIMIT :limit",
    )
    fun search(query: String, limit: Int = 50): Flow<List<HistoryEntry>>


    @Query(
        "SELECT * FROM history h " +
            "WHERE (h.url LIKE '%' || :query || '%' OR h.title LIKE '%' || :query || '%') " +
            "AND h.id = (" +
            "SELECT h2.id FROM history h2 " +
            "WHERE h2.url = h.url " +
            "AND (h2.url LIKE '%' || :query || '%' OR h2.title LIKE '%' || :query || '%') " +
            "ORDER BY h2.visitedAt DESC, h2.id DESC LIMIT 1" +
            ") " +
            "ORDER BY h.visitedAt DESC, h.id DESC LIMIT :limit",
    )
    fun searchSuggestions(query: String, limit: Int): Flow<List<HistoryEntry>>

    @Query(
        "SELECT * FROM history h " +
            "WHERE h.id = (" +
            "SELECT h2.id FROM history h2 " +
            "WHERE h2.url = h.url " +
            "ORDER BY h2.visitedAt DESC, h2.id DESC LIMIT 1" +
            ") " +
            "ORDER BY h.visitedAt DESC, h.id DESC LIMIT :limit",
    )
    fun getRecentSuggestions(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit OFFSET :offset")
    fun getRecent(limit: Int = 100, offset: Int = 0): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)
}

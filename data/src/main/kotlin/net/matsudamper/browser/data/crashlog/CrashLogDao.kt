package net.matsudamper.browser.data.crashlog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CrashLogDao {
    @Insert
    fun insert(entity: CrashLogEntity): Long

    @Query("SELECT * FROM crash_log ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<CrashLogEntity>>

    @Query("SELECT * FROM crash_log WHERE id = :id")
    suspend fun getById(id: Long): CrashLogEntity?

    @Query("DELETE FROM crash_log")
    suspend fun deleteAll()
}

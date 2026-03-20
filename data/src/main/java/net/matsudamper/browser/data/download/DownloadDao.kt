package net.matsudamper.browser.data.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE download SET status = :status WHERE workerId = :workerId")
    suspend fun updateStatus(workerId: String, status: String)

    @Query(
        "UPDATE download SET fileName = :fileName, status = 'RUNNING', " +
            "progress = :progress, totalRead = :totalRead, contentLength = :contentLength " +
            "WHERE workerId = :workerId",
    )
    suspend fun updateProgress(
        workerId: String,
        fileName: String,
        progress: Int,
        totalRead: Long,
        contentLength: Long,
    )

    @Query(
        "UPDATE download SET fileName = :fileName, fileUri = :fileUri, status = 'SUCCEEDED' " +
            "WHERE workerId = :workerId",
    )
    suspend fun updateCompleted(workerId: String, fileName: String, fileUri: String)

    @Query("SELECT * FROM download ORDER BY enqueuedAt ASC")
    fun observeAll(): Flow<List<DownloadEntity>>
}

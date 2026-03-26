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

    /** 既存レコードがある場合は何もしない（ENQUEUEDの事前挿入に使用） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreConflict(entity: DownloadEntity)

    /** ENQUEUEDからRUNNINGへの状態遷移。ENQUEUED以外は変更しない */
    @Query("UPDATE download SET status = 'RUNNING' WHERE workerId = :workerId AND status = 'ENQUEUED'")
    suspend fun updateEnqueuedToRunning(workerId: String)

    @Query("UPDATE download SET status = :status WHERE workerId = :workerId")
    suspend fun updateStatus(workerId: String, status: String)

    @Query("UPDATE download SET status = 'FAILED' WHERE workerId = :workerId")
    suspend fun updateFailed(workerId: String)

    /** SUCCEEDED/FAILED 以外の状態のときのみキャンセルする。完了済みの上書きを防ぐ */
    @Query("UPDATE download SET status = 'CANCELLED' WHERE workerId = :workerId AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    suspend fun cancelIfActive(workerId: String)

    @Query(
        "UPDATE download SET fileName = :fileName, status = 'RUNNING', " +
            "progress = :progress, totalRead = :totalRead, contentLength = :contentLength " +
            "WHERE workerId = :workerId AND status = 'RUNNING'",
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

    @Query("SELECT * FROM download ORDER BY enqueuedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE workerId = :workerId")
    suspend fun get(workerId: String): DownloadEntity
}

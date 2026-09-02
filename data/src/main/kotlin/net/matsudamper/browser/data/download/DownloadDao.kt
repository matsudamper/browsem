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
    @Query("UPDATE download SET status = 'RUNNING' WHERE currentWorkerId = :currentWorkerId AND status = 'ENQUEUED'")
    suspend fun updateEnqueuedToRunning(currentWorkerId: String)

    @Query("UPDATE download SET status = :status WHERE workerId = :workerId")
    suspend fun updateStatus(workerId: String, status: String)

    /** 実行中（ENQUEUED/RUNNING）のときのみ失敗にする。PAUSED/CANCELLED の上書きを防ぐ */
    @Query(
        "UPDATE download SET status = 'FAILED', failureReason = :failureReason " +
            "WHERE currentWorkerId = :currentWorkerId AND status IN ('ENQUEUED', 'RUNNING')",
    )
    suspend fun updateFailed(currentWorkerId: String, failureReason: String?)

    /** SUCCEEDED/FAILED 以外の状態のときのみキャンセルする。完了済みの上書きを防ぐ */
    @Query("UPDATE download SET status = 'CANCELLED' WHERE currentWorkerId = :currentWorkerId AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    suspend fun cancelIfActive(currentWorkerId: String)

    /** 実行中（ENQUEUED/RUNNING）のときのみ一時停止する。完了・失敗・キャンセル済みの上書きを防ぐ */
    @Query("UPDATE download SET status = 'PAUSED' WHERE currentWorkerId = :currentWorkerId AND status IN ('ENQUEUED', 'RUNNING')")
    suspend fun pauseIfActive(currentWorkerId: String)

    /** 指定ワーカーの現在のステータスを取得する。レコードが無い場合は null */
    @Query("SELECT status FROM download WHERE currentWorkerId = :currentWorkerId")
    suspend fun getStatus(currentWorkerId: String): String?

    @Query(
        "UPDATE download SET fileName = :fileName, status = 'RUNNING', " +
            "progress = :progress, totalRead = :totalRead, contentLength = :contentLength " +
            "WHERE currentWorkerId = :currentWorkerId AND status = 'RUNNING'",
    )
    suspend fun updateProgress(
        currentWorkerId: String,
        fileName: String,
        progress: Int,
        totalRead: Long,
        contentLength: Long,
    )

    /** キャンセル/一時停止済みレコードを完了で上書きしない（停止要求とWorker完了の競合対策） */
    @Query(
        "UPDATE download SET fileName = :fileName, fileUri = :fileUri, status = 'SUCCEEDED' " +
            "WHERE currentWorkerId = :currentWorkerId AND status NOT IN ('CANCELLED', 'PAUSED')",
    )
    suspend fun updateCompleted(currentWorkerId: String, fileName: String, fileUri: String)

    @Query("SELECT * FROM download ORDER BY enqueuedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download ORDER BY enqueuedAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE currentWorkerId = :currentWorkerId")
    suspend fun getByCurrentWorkerId(currentWorkerId: String): DownloadEntity?

    /**
     * ダウンロード失敗時に部分ファイルURIを保存する。
     * 再開可能なダウンロードとしてFAILEDステータスで記録する
     */
    @Query(
        "UPDATE download SET status = 'FAILED', partialFileUri = :partialFileUri, " +
            "fileName = :fileName, totalRead = :totalRead, contentLength = :contentLength, " +
            "failureReason = :failureReason " +
            "WHERE currentWorkerId = :currentWorkerId AND status = 'RUNNING'",
    )
    suspend fun updatePartialFailed(
        currentWorkerId: String,
        partialFileUri: String,
        fileName: String,
        totalRead: Long,
        contentLength: Long,
        failureReason: String?,
    )

    /**
     * 一時停止時に部分ファイルURIを保存する。
     * 再開可能なダウンロードとしてPAUSEDステータスで記録する
     */
    @Query(
        "UPDATE download SET partialFileUri = :partialFileUri, " +
            "fileName = :fileName, totalRead = :totalRead, contentLength = :contentLength " +
            "WHERE currentWorkerId = :currentWorkerId AND status = 'PAUSED'",
    )
    suspend fun updatePausedPartial(
        currentWorkerId: String,
        partialFileUri: String,
        fileName: String,
        totalRead: Long,
        contentLength: Long,
    )

    /**
     * 再開時に既存レコードを新しいワーカーIDへ付け替えてENQUEUEDに戻す。
     * レコードを削除・再作成しないため、enqueuedAt（リスト上の位置）と
     * UIのアイテム同一性（workerId）が維持される
     */
    @Query(
        "UPDATE download SET currentWorkerId = :newWorkerId, status = 'ENQUEUED', failureReason = NULL " +
            "WHERE workerId = :workerId",
    )
    suspend fun updateResumed(workerId: String, newWorkerId: String)

    /** 指定URLに一致するアクティブ（ENQUEUED/RUNNING/SUCCEEDED/PAUSED）なダウンロードを取得する */
    @Query("SELECT * FROM download WHERE url = :url AND status IN ('ENQUEUED', 'RUNNING', 'SUCCEEDED', 'PAUSED') ORDER BY enqueuedAt DESC")
    suspend fun findActiveByUrl(url: String): List<DownloadEntity>

    /** 実行中以外の履歴を削除する。ダウンロード済みファイル自体は削除しない */
    @Query("DELETE FROM download WHERE status NOT IN ('ENQUEUED', 'RUNNING')")
    suspend fun deleteHistory()
}

package net.matsudamper.browser.data.download

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class DownloadRecordStatus { ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, PAUSED }

data class DownloadRecord(
    /** レコードの安定ID。再開してもこの値は変わらない */
    val workerId: UUID,
    /** 現在のWorkManagerワーカーのID。再開のたびに更新される */
    val currentWorkerId: UUID,
    val url: String,
    val fileName: String,
    val fileUri: String?,
    val status: DownloadRecordStatus,
    val progress: Int,
    val totalRead: Long,
    val contentLength: Long,
    val enqueuedAt: Long,
    val referrerUrl: String,
    /** 失敗・一時停止時に保存した部分ファイルURI。非nullの場合は再開可能 */
    val partialFileUri: String?,
)

class DownloadRepository(context: Context) {

    private val dao = DownloadDatabase.getInstance(context).downloadDao()

    fun observeDownloads(): Flow<List<DownloadRecord>> {
        return dao.observeAll().map { list -> list.map { it.toRecord() } }
    }

    /** エンキュー時に ENQUEUED 状態でレコードを事前挿入する。既存レコードがある場合は何もしない */
    suspend fun insertEnqueued(workerId: String, url: String, referrerUrl: String, enqueuedAt: Long) {
        dao.insertIgnoreConflict(
            DownloadEntity(
                workerId = workerId,
                currentWorkerId = workerId,
                url = url,
                fileName = "",
                fileUri = null,
                status = DownloadRecordStatus.ENQUEUED.name,
                progress = 0,
                totalRead = 0L,
                contentLength = -1L,
                enqueuedAt = enqueuedAt,
                referrerUrl = referrerUrl,
                partialFileUri = null,
            ),
        )
    }

    /** Worker 開始時に RUNNING 状態に遷移する。レコードがあれば遷移、なければ新規挿入する */
    suspend fun insertDownload(workerId: String, url: String, referrerUrl: String, enqueuedAt: Long) {
        // 再開時はエンキュー済みレコードが currentWorkerId で紐付いているため、
        // 存在しない場合（エンキュー前にWorkerが起動したケース）のみ新規挿入する
        if (dao.getByCurrentWorkerId(workerId) == null) {
            dao.insertIgnoreConflict(
                DownloadEntity(
                    workerId = workerId,
                    currentWorkerId = workerId,
                    url = url,
                    fileName = "",
                    fileUri = null,
                    status = DownloadRecordStatus.RUNNING.name,
                    progress = 0,
                    totalRead = 0L,
                    contentLength = -1L,
                    enqueuedAt = enqueuedAt,
                    referrerUrl = referrerUrl,
                    partialFileUri = null,
                ),
            )
        }
        // ENQUEUEDからRUNNINGへの状態遷移（既にRUNNINGの場合は何もしない）
        dao.updateEnqueuedToRunning(workerId)
    }

    suspend fun updateProgress(
        currentWorkerId: String,
        fileName: String,
        progress: Int,
        totalRead: Long,
        contentLength: Long,
    ) {
        dao.updateProgress(
            currentWorkerId = currentWorkerId,
            fileName = fileName,
            progress = progress,
            totalRead = totalRead,
            contentLength = contentLength,
        )
    }

    suspend fun updateCompleted(currentWorkerId: String, fileName: String, fileUri: String) {
        dao.updateCompleted(currentWorkerId = currentWorkerId, fileName = fileName, fileUri = fileUri)
    }

    suspend fun updateFailed(currentWorkerId: String) {
        dao.updateFailed(currentWorkerId = currentWorkerId)
    }

    /**
     * ダウンロード失敗時に部分ファイルURIを保存する。
     * 再開可能なFAILEDレコードとして記録する
     */
    suspend fun updatePartialFailed(
        currentWorkerId: String,
        partialFileUri: String,
        fileName: String,
        totalRead: Long,
        contentLength: Long,
    ) {
        dao.updatePartialFailed(
            currentWorkerId = currentWorkerId,
            partialFileUri = partialFileUri,
            fileName = fileName,
            totalRead = totalRead,
            contentLength = contentLength,
        )
    }

    /** SUCCEEDED/FAILED 以外の状態のときのみキャンセル状態に更新する */
    suspend fun updateCancelled(currentWorkerId: String) {
        dao.cancelIfActive(currentWorkerId)
    }

    /** ENQUEUED/RUNNING のときのみ一時停止状態に更新する */
    suspend fun updatePaused(currentWorkerId: String) {
        dao.pauseIfActive(currentWorkerId)
    }

    /** 指定したワーカーのレコードが一時停止済みかどうかを返す */
    suspend fun isPaused(currentWorkerId: String): Boolean {
        return dao.getStatus(currentWorkerId) == DownloadRecordStatus.PAUSED.name
    }

    /**
     * 一時停止時に部分ファイルURIを保存する。
     * 再開可能なPAUSEDレコードとして記録する
     */
    suspend fun updatePausedPartial(
        currentWorkerId: String,
        partialFileUri: String,
        fileName: String,
        totalRead: Long,
        contentLength: Long,
    ) {
        dao.updatePausedPartial(
            currentWorkerId = currentWorkerId,
            partialFileUri = partialFileUri,
            fileName = fileName,
            totalRead = totalRead,
            contentLength = contentLength,
        )
    }

    suspend fun isCancelled(currentWorkerId: String): Boolean {
        return dao.getStatus(currentWorkerId) == DownloadRecordStatus.CANCELLED.name
    }

    /**
     * 指定したワーカーのレコードがキャンセルまたは一時停止済みかどうかを返す。
     * WorkManager の割り込みが取りこぼされた場合でも Worker が自力で停止できるよう、
     * Worker の進捗更新時にポーリングして確認するために使用する
     */
    suspend fun isStopRequested(currentWorkerId: String): Boolean {
        return dao.getStatus(currentWorkerId) in listOf(
            DownloadRecordStatus.CANCELLED.name,
            DownloadRecordStatus.PAUSED.name,
        )
    }

    suspend fun getByCurrentWorkerId(currentWorkerId: UUID): DownloadEntity? {
        return dao.getByCurrentWorkerId(currentWorkerId = currentWorkerId.toString())
    }

    /**
     * 再開時に既存レコードを新しいワーカーIDへ付け替えてENQUEUEDに戻す。
     * レコードを削除・再作成しないため、リスト上の位置とUIのアイテム同一性が維持される
     */
    suspend fun updateResumed(workerId: String, newWorkerId: String) {
        dao.updateResumed(workerId = workerId, newWorkerId = newWorkerId)
    }

    private fun DownloadEntity.toRecord(): DownloadRecord {
        val recordStatus = try {
            DownloadRecordStatus.valueOf(this.status)
        } catch (_: IllegalArgumentException) {
            DownloadRecordStatus.FAILED
        }
        return DownloadRecord(
            workerId = UUID.fromString(workerId),
            currentWorkerId = UUID.fromString(currentWorkerId),
            url = url,
            fileName = fileName,
            fileUri = fileUri,
            status = recordStatus,
            progress = progress,
            totalRead = totalRead,
            contentLength = contentLength,
            enqueuedAt = enqueuedAt,
            referrerUrl = referrerUrl,
            partialFileUri = partialFileUri,
        )
    }
}

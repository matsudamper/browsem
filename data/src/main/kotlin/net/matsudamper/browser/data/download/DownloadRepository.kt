package net.matsudamper.browser.data.download

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class DownloadRecordStatus { ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class DownloadRecord(
    val workerId: UUID,
    val url: String,
    val fileName: String,
    val fileUri: String?,
    val status: DownloadRecordStatus,
    val progress: Int,
    val totalRead: Long,
    val contentLength: Long,
    val enqueuedAt: Long,
    val referrerUrl: String,
    /** 失敗時に保存した部分ファイルURI。非nullの場合は再開可能 */
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

    /** Worker 開始時に RUNNING 状態に遷移する。ENQUEUED レコードがあれば遷移、なければ新規挿入する */
    suspend fun insertDownload(workerId: String, url: String, referrerUrl: String, enqueuedAt: Long) {
        // ENQUEUEDレコードがない場合に備えてRUNNINGで新規挿入（競合時は無視）
        dao.insertIgnoreConflict(
            DownloadEntity(
                workerId = workerId,
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
        // ENQUEUEDからRUNNINGへの状態遷移（既にRUNNINGの場合は何もしない）
        dao.updateEnqueuedToRunning(workerId)
    }

    suspend fun updateProgress(
        workerId: String,
        fileName: String,
        progress: Int,
        totalRead: Long,
        contentLength: Long,
    ) {
        dao.updateProgress(
            workerId = workerId,
            fileName = fileName,
            progress = progress,
            totalRead = totalRead,
            contentLength = contentLength,
        )
    }

    suspend fun updateCompleted(workerId: String, fileName: String, fileUri: String) {
        dao.updateCompleted(workerId = workerId, fileName = fileName, fileUri = fileUri)
    }

    suspend fun updateFailed(workerId: String) {
        dao.updateFailed(workerId = workerId)
    }

    /**
     * ダウンロード失敗時に部分ファイルURIを保存する。
     * 再開可能なFAILEDレコードとして記録する
     */
    suspend fun updatePartialFailed(
        workerId: String,
        partialFileUri: String,
        fileName: String,
        totalRead: Long,
        contentLength: Long,
    ) {
        dao.updatePartialFailed(
            workerId = workerId,
            partialFileUri = partialFileUri,
            fileName = fileName,
            totalRead = totalRead,
            contentLength = contentLength,
        )
    }

    /** SUCCEEDED/FAILED 以外の状態のときのみキャンセル状態に更新する */
    suspend fun updateCancelled(workerId: String) {
        dao.cancelIfActive(workerId)
    }

    suspend fun get(workerId: UUID): DownloadEntity {
        return dao.get(workerId = workerId.toString())
    }

    /** 指定したワーカーIDのレコードを削除する（再開時に古いレコードを削除するために使用） */
    suspend fun deleteById(workerId: String) {
        dao.deleteById(workerId)
    }

    private fun DownloadEntity.toRecord(): DownloadRecord {
        val recordStatus = try {
            DownloadRecordStatus.valueOf(this.status)
        } catch (_: IllegalArgumentException) {
            DownloadRecordStatus.FAILED
        }
        return DownloadRecord(
            workerId = UUID.fromString(workerId),
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

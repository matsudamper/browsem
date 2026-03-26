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
)

class DownloadRepository(context: Context) {

    private val dao = DownloadDatabase.getInstance(context).downloadDao()

    fun observeDownloads(): Flow<List<DownloadRecord>> {
        return dao.observeAll().map { list -> list.map { it.toRecord() } }
    }

    /** エンキュー時に ENQUEUED 状態でレコードを事前挿入する。既存レコードがある場合は何もしない */
    suspend fun insertEnqueued(workerId: UUID, url: String, fileName: String, enqueuedAt: Long) {
        dao.insertIgnoreConflict(
            DownloadEntity(
                workerId = workerId.toString(),
                url = url,
                fileName = fileName,
                fileUri = null,
                status = DownloadRecordStatus.ENQUEUED.name,
                progress = 0,
                totalRead = 0L,
                contentLength = -1L,
                enqueuedAt = enqueuedAt,
            ),
        )
    }

    /** Worker 開始時に RUNNING 状態に遷移する。ENQUEUED レコードがあれば遷移、なければ新規挿入する */
    suspend fun insertDownload(workerId: UUID, url: String, fileName: String, enqueuedAt: Long) {
        // ENQUEUEDレコードがない場合に備えてRUNNINGで新規挿入（競合時は無視）
        dao.insertIgnoreConflict(
            DownloadEntity(
                workerId = workerId.toString(),
                url = url,
                fileName = fileName,
                fileUri = null,
                status = DownloadRecordStatus.RUNNING.name,
                progress = 0,
                totalRead = 0L,
                contentLength = -1L,
                enqueuedAt = enqueuedAt,
            ),
        )
        // ENQUEUEDからRUNNINGへの状態遷移（既にRUNNINGの場合は何もしない）
        dao.updateEnqueuedToRunning(workerId.toString())
    }

    suspend fun updateProgress(
        workerId: UUID,
        fileName: String,
        progress: Int,
        totalRead: Long,
        contentLength: Long,
    ) {
        dao.updateProgress(
            workerId = workerId.toString(),
            fileName = fileName,
            progress = progress,
            totalRead = totalRead,
            contentLength = contentLength,
        )
    }

    suspend fun updateCompleted(workerId: UUID, fileName: String, fileUri: String) {
        dao.updateCompleted(workerId = workerId.toString(), fileName = fileName, fileUri = fileUri)
    }

    suspend fun updateFailed(workerId: UUID) {
        dao.updateFailed(workerId = workerId.toString())
    }

    /** SUCCEEDED/FAILED 以外の状態のときのみキャンセル状態に更新する */
    suspend fun updateCancelled(workerId: UUID) {
        dao.cancelIfActive(workerId.toString())
    }

    suspend fun get(workerId: UUID): DownloadEntity {
        return dao.get(workerId = workerId.toString())
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
        )
    }
}

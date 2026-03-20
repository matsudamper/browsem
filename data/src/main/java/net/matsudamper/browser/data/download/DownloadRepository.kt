package net.matsudamper.browser.data.download

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class DownloadRecordStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class DownloadRecord(
    val workerId: String,
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

    /** ダウンロード開始時に RUNNING 状態でレコードを挿入する */
    suspend fun insertDownload(workerId: String, url: String, enqueuedAt: Long) {
        dao.upsert(
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
            ),
        )
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
        dao.updateStatus(workerId, DownloadRecordStatus.FAILED.name)
    }

    suspend fun updateCancelled(workerId: String) {
        dao.updateStatus(workerId, DownloadRecordStatus.CANCELLED.name)
    }

    private fun DownloadEntity.toRecord(): DownloadRecord {
        val recordStatus = try {
            DownloadRecordStatus.valueOf(this.status)
        } catch (_: IllegalArgumentException) {
            DownloadRecordStatus.FAILED
        }
        return DownloadRecord(
            workerId = workerId,
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

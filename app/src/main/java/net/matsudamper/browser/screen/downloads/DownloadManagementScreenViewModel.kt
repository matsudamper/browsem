package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.R
import net.matsudamper.browser.data.download.DownloadRecord
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.download.DownloadRepository
import java.util.UUID
import androidx.core.net.toUri

internal class DownloadManagementScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    private val downloadRepository = DownloadRepository(application)

    /** resumeDownload から最新のレコードを参照するためのキャッシュ */
    private var currentRecords: List<DownloadRecord> = emptyList()

    val uiState: StateFlow<DownloadManagementScreenUiState> = downloadRepository
        .observeDownloads()
        .map { records ->
            currentRecords = records
            val items = records.map { record -> record.toDownloadItem() }
            DownloadManagementScreenUiState(
                downloads = items,
                callbacks = buildCallbacks(),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DownloadManagementScreenUiState(
                downloads = emptyList(),
                callbacks = buildCallbacks(),
            ),
        )

    private fun buildCallbacks() = DownloadManagementScreenUiState.Callbacks(
        onCancel = { id -> cancelDownload(id) },
        onOpenFile = { fileUri -> openFile(fileUri) },
        onOpenDownloadsFolder = { openDownloadsFolder() },
        onResume = { id -> resumeDownload(id) },
    )

    private fun DownloadRecord.toDownloadItem(): DownloadManagementScreenUiState.DownloadItem {
        val uiStatus = when (status) {
            DownloadRecordStatus.SUCCEEDED -> {
                val uri = fileUri
                if (uri != null) {
                    DownloadManagementScreenUiState.DownloadStatus.Completed(uri)
                } else {
                    DownloadManagementScreenUiState.DownloadStatus.Failed(canResume = false)
                }
            }
            DownloadRecordStatus.FAILED -> {
                DownloadManagementScreenUiState.DownloadStatus.Failed(
                    canResume = partialFileUri != null,
                )
            }
            DownloadRecordStatus.ENQUEUED -> {
                DownloadManagementScreenUiState.DownloadStatus.InProgress(
                    progress = 0,
                    totalRead = 0L,
                    contentLength = -1L,
                    isIndeterminate = true,
                )
            }
            DownloadRecordStatus.RUNNING -> {
                DownloadManagementScreenUiState.DownloadStatus.InProgress(
                    progress = progress,
                    totalRead = totalRead,
                    contentLength = contentLength,
                    isIndeterminate = contentLength <= 0,
                )
            }
            DownloadRecordStatus.CANCELLED -> DownloadManagementScreenUiState.DownloadStatus.Cancelled
        }
        return DownloadManagementScreenUiState.DownloadItem(
            id = workerId,
            fileName = fileName.ifEmpty {
                // FAILEDかつファイル名未取得の場合は失敗を明示する
                if (status == DownloadRecordStatus.FAILED) "ダウンロード失敗" else "ダウンロード中..."
            },
            status = uiStatus,
            enqueuedAt = enqueuedAt,
        )
    }

    private fun cancelDownload(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /**
     * 失敗したダウンロードを再開する。
     * 部分ファイルURIが保存されている場合はRangeリクエストで再開し、
     * そうでない場合は最初からダウンロードし直す。
     */
    private fun resumeDownload(id: UUID) {
        val record = currentRecords.find { it.workerId == id } ?: return
        val app = getApplication<Application>()
        DownloadWorker.ensureNotificationChannel(app)

        val workId = UUID.randomUUID()
        val notificationId = workId.hashCode() and 0x7fffffff
        val partialFileUri = record.partialFileUri

        val inputData = if (partialFileUri != null) {
            workDataOf(
                DownloadWorker.KEY_URL to record.url,
                DownloadWorker.KEY_REFERRER_URL to record.referrerUrl,
                DownloadWorker.KEY_NOTIFICATION_ID to notificationId,
                DownloadWorker.KEY_PARTIAL_FILE_URI to partialFileUri,
                DownloadWorker.KEY_RESUME_FROM_BYTES to record.totalRead,
            )
        } else {
            workDataOf(
                DownloadWorker.KEY_URL to record.url,
                DownloadWorker.KEY_REFERRER_URL to record.referrerUrl,
                DownloadWorker.KEY_NOTIFICATION_ID to notificationId,
            )
        }

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(workId)
            .setInputData(inputData)
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            .build()

        viewModelScope.launch {
            // 古いFAILEDレコードを削除してから新しいENQUEUEDレコードを挿入する
            downloadRepository.deleteById(record.workerId.toString())
            downloadRepository.insertEnqueued(
                workerId = workRequest.id.toString(),
                url = record.url,
                referrerUrl = record.referrerUrl,
                enqueuedAt = System.currentTimeMillis(),
            )
        }
        workManager.enqueue(workRequest)

        val notificationTitle = if (partialFileUri != null) {
            app.getString(R.string.download_notification_resuming)
        } else {
            app.getString(R.string.download_notification_starting)
        }
        val notification = NotificationCompat.Builder(app, DownloadWorker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(notificationTitle)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun openFile(fileUri: String) {
        val app = getApplication<Application>()
        val uri = fileUri.toUri()
        val mimeType = app.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        runCatching { app.startActivity(intent) }
    }

    private fun openDownloadsFolder() {
        val app = getApplication<Application>()
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { app.startActivity(intent) }
    }
}

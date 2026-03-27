package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.data.download.DownloadRecord
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.download.DownloadRepository
import java.util.UUID

internal class DownloadManagementScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {

    // キャンセル操作のみWorkManagerを使用する
    private val workManager = WorkManager.getInstance(application)
    private val downloadRepository = DownloadRepository(application)
    private val callbacks = buildCallbacks()

    val uiState: StateFlow<DownloadManagementScreenUiState> = MutableStateFlow(
        DownloadManagementScreenUiState(
            downloads = emptyList(),
            callbacks = callbacks,
        )
    ).also { uiStateFlow ->
        viewModelScope.launch {
            downloadRepository.observeDownloads().collectLatest { records ->
                val items = records.map { record -> record.toDownloadItem() }
                uiStateFlow.update {
                    DownloadManagementScreenUiState(
                        downloads = items,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }.asStateFlow()

    private fun buildCallbacks() = DownloadManagementScreenUiState.Callbacks(
        onCancel = { id -> cancelDownload(id) },
        onOpenFile = { fileUri -> openFile(fileUri) },
        onOpenDownloadsFolder = { openDownloadsFolder() },
    )

    private fun DownloadRecord.toDownloadItem(): DownloadManagementScreenUiState.DownloadItem {
        val uiStatus = when (status) {
            DownloadRecordStatus.SUCCEEDED -> {
                val uri = fileUri
                if (uri != null) {
                    DownloadManagementScreenUiState.DownloadStatus.Completed(uri)
                } else {
                    DownloadManagementScreenUiState.DownloadStatus.Failed
                }
            }
            DownloadRecordStatus.FAILED -> DownloadManagementScreenUiState.DownloadStatus.Failed
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

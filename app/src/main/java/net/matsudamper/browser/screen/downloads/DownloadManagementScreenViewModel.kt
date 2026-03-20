package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.matsudamper.browser.DownloadWorker
import java.util.UUID

internal class DownloadManagementScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)

    val uiState: StateFlow<DownloadManagementScreenUiState> = workManager
        .getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD)
        .map { workInfoList ->
            val items = workInfoList
                .filter { info ->
                    info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.SUCCEEDED ||
                        info.state == WorkInfo.State.FAILED
                }
                .map { info -> info.toDownloadItem() }
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
    )

    private fun WorkInfo.toDownloadItem(): DownloadManagementScreenUiState.DownloadItem {
        val fileName = progress.getString(DownloadWorker.KEY_FILE_NAME)
            ?: outputData.getString(DownloadWorker.KEY_FILE_NAME)
            ?: "ダウンロード..."
        val status = when (state) {
            WorkInfo.State.SUCCEEDED -> {
                val fileUri = outputData.getString(DownloadWorker.KEY_FILE_URI)
                if (fileUri != null) {
                    DownloadManagementScreenUiState.DownloadStatus.Completed(fileUri)
                } else {
                    DownloadManagementScreenUiState.DownloadStatus.Failed
                }
            }
            WorkInfo.State.FAILED -> DownloadManagementScreenUiState.DownloadStatus.Failed
            else -> {
                val p = progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                val totalRead = progress.getLong(DownloadWorker.KEY_TOTAL_READ, 0L)
                val contentLength = progress.getLong(DownloadWorker.KEY_CONTENT_LENGTH, -1L)
                DownloadManagementScreenUiState.DownloadStatus.InProgress(
                    progress = p,
                    totalRead = totalRead,
                    contentLength = contentLength,
                    isIndeterminate = contentLength <= 0,
                )
            }
        }
        return DownloadManagementScreenUiState.DownloadItem(
            id = id,
            fileName = fileName,
            status = status,
        )
    }

    private fun cancelDownload(id: UUID) {
        workManager.cancelWorkById(id)
    }

    private fun openFile(fileUri: String) {
        val app = getApplication<Application>()
        val uri = android.net.Uri.parse(fileUri)
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

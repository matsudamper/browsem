package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.data.download.DownloadRecord
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.ui.downloads.DownloadManagementScreenUiState
import java.util.UUID

internal class DownloadManagementScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    private val downloadRepository = DownloadRepository(application)
    private val geckoDownloadManager = GeckoDownloadManager(application, downloadRepository)
    private val callbacks = buildCallbacks()

    /** resumeDownload から最新のレコードを参照するためのキャッシュ */
    private var currentRecords: List<DownloadRecord> = emptyList()

    val uiState: StateFlow<DownloadManagementScreenUiState> = MutableStateFlow(
        DownloadManagementScreenUiState(
            loadingState = DownloadManagementScreenUiState.LoadingState.Loading,
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            downloadRepository.observeDownloads().collectLatest { records ->
                currentRecords = records
                val items = records.map { record -> record.toDownloadItem() }
                uiStateFlow.update {
                    DownloadManagementScreenUiState(
                        loadingState = DownloadManagementScreenUiState.LoadingState.Loaded(items),
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
        viewModelScope.launch {
            // キャンセル前にWorkerの状態を確認する
            val workInfo = workManager.getWorkInfoByIdFlow(id).first()
            workManager.cancelWorkById(id)
            // WorkerがRUNNING状態の場合はdoWork()のCancellationExceptionハンドラがDBを更新するため直接更新しない
            // ENQUEUED（未起動）またはWorkerがWorkManagerに存在しない（prune済み等）場合は
            // doWork()が呼ばれないためDBを直接CANCELLED状態に更新する
            if (workInfo == null || workInfo.state != WorkInfo.State.RUNNING) {
                downloadRepository.updateCancelled(id.toString())
            }
        }
    }

    /**
     * 失敗したダウンロードを再開する。
     * 部分ファイルが残っている場合はRangeリクエストで再開し、
     * そうでない場合は同じURLを再度エンキューする。
     */
    private fun resumeDownload(id: UUID) {
        val record = currentRecords.find { it.workerId == id } ?: return
        if (record.status != DownloadRecordStatus.FAILED) return

        val partialFileUri = record.partialFileUri
        if (partialFileUri != null) {
            geckoDownloadManager.resumeDownload(
                oldWorkerId = record.workerId.toString(),
                url = record.url,
                referrerUrl = record.referrerUrl,
                partialFileUri = partialFileUri,
                totalRead = record.totalRead,
                coroutineScope = viewModelScope,
            )
            return
        }

        viewModelScope.launch {
            downloadRepository.deleteById(record.workerId.toString())
        }
        geckoDownloadManager.enqueueDownload(
            url = record.url,
            referrerUrl = record.referrerUrl,
            coroutineScope = viewModelScope,
        )
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

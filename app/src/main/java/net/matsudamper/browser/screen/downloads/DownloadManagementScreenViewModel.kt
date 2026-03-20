package net.matsudamper.browser.screen.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
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
    context: Context,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    val uiState: StateFlow<DownloadManagementScreenUiState> = workManager
        .getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD)
        .map { workInfoList ->
            val items = workInfoList
                .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .map { info ->
                    val fileName = info.progress.getString(DownloadWorker.KEY_FILE_NAME)
                        ?: info.outputData.getString(DownloadWorker.KEY_FILE_NAME)
                        ?: "ダウンロード中..."
                    val progress = info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                    val totalRead = info.progress.getLong(DownloadWorker.KEY_TOTAL_READ, 0L)
                    val contentLength = info.progress.getLong(DownloadWorker.KEY_CONTENT_LENGTH, -1L)
                    DownloadManagementScreenUiState.DownloadItem(
                        id = info.id,
                        fileName = fileName,
                        progress = progress,
                        totalRead = totalRead,
                        contentLength = contentLength,
                        isIndeterminate = contentLength <= 0,
                    )
                }
            DownloadManagementScreenUiState(
                downloads = items,
                callbacks = DownloadManagementScreenUiState.Callbacks(
                    onCancel = { id -> cancelDownload(id) },
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DownloadManagementScreenUiState(
                downloads = emptyList(),
                callbacks = DownloadManagementScreenUiState.Callbacks(
                    onCancel = { id -> cancelDownload(id) },
                ),
            ),
        )

    private fun cancelDownload(id: UUID) {
        workManager.cancelWorkById(id)
    }
}

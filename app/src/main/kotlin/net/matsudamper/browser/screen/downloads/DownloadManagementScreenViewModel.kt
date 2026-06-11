package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    /** resumeDownload から最新のレコードを参照するためのキャッシュ */
    private var currentRecords: List<DownloadRecord> = emptyList()

    val uiState: StateFlow<DownloadManagementScreenUiState> = MutableStateFlow(
        DownloadManagementScreenUiState(
            downloads = emptyList(),
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            downloadRepository.observeDownloads().collectLatest { records ->
                currentRecords = records
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
        onResume = { id -> resumeDownload(id) },
        onOpenOriginPage = { url -> eventHandler.trySend { it.navigateToUrl(url) } },
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
            originPageUrl = referrerUrl.ifBlank { null },
        )
    }

    private fun cancelDownload(id: UUID) {
        // suspend を挟むと viewModelScope の破棄でキャンセル要求自体が消えるため、即時に発行する
        workManager.cancelWorkById(id)
        // Worker 起動前に GeckoDownloadManager が直接表示した通知は誰も消さないため、
        // 同じ導出式（workId の hashCode）で通知 ID を求めて明示的に消す
        val notificationId = id.hashCode() and 0x7fffffff
        getApplication<Application>()
            .getSystemService(NotificationManager::class.java)
            ?.cancel(notificationId)
        viewModelScope.launch {
            // Worker の CancellationException ハンドラに依存せず無条件で CANCELLED に更新する。
            // SUCCEEDED/FAILED の上書きは DAO 側のガードで防がれる。
            // WorkManager の割り込みが届かない場合でも、Worker が進捗更新時に
            // この CANCELLED 状態を検知して自力で停止する
            downloadRepository.updateCancelled(id.toString())
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
        // MediaStore の content:// URI では lastPathSegment が数値IDになるため、
        // DISPLAY_NAME を取得してファイル名による拡張子チェックも行う
        val displayName = app.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val isApk = mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) ||
            (displayName?.endsWith(".apk", ignoreCase = true) == true)

        // APKの場合、提供元不明アプリのインストール権限がなければ設定画面へ誘導する
        if (isApk && !app.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${app.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { app.startActivity(settingsIntent) }
            return
        }

        // 拡張子でAPKと判定された場合は確実にインストーラーが起動するようMIMEタイプを補正する
        val effectiveMimeType = if (isApk) "application/vnd.android.package-archive" else mimeType
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMimeType)
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

    interface Event {
        /** ダウンロード開始時のページURLを新しいタブで開く */
        fun navigateToUrl(url: String)
    }
}

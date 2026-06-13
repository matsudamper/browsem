package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    /** resumeDownload から最新のレコードを参照するためのキャッシュ */
    private var currentRecords: List<DownloadRecord> = emptyList()

    /** 読み込み済みサムネイル。サムネイル非対応のファイルは null を保持して再読み込みを防ぐ */
    private val thumbnailFlow = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())

    /** サムネイル読み込み要求済みの fileUri */
    private val requestedThumbnailUris = mutableSetOf<String>()

    /**
     * 一時停止を要求したがワーカーがまだ停止処理中のダウンロードID（安定 workerId）。
     * DB 上は即座に PAUSED になるが、ワーカーが部分ファイルを保存し終えるまでは
     * UI 上「停止処理中（グレーアウト + 円形インジケーター）」として表示する。
     */
    private val pausingIds = MutableStateFlow<Set<UUID>>(emptySet())

    val uiState: StateFlow<DownloadManagementScreenUiState> = MutableStateFlow(
        DownloadManagementScreenUiState(
            downloads = emptyList(),
            callbacks = callbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                downloadRepository.observeDownloads(),
                thumbnailFlow,
                pausingIds,
            ) { records, thumbnails, pausing -> Triple(records, thumbnails, pausing) }
                .collectLatest { (records, thumbnails, pausing) ->
                    currentRecords = records
                    records.forEach { record ->
                        if (record.status == DownloadRecordStatus.SUCCEEDED) {
                            record.fileUri?.let { requestThumbnail(it) }
                        }
                    }
                    val items = records.map { record -> record.toDownloadItem(thumbnails, pausing) }
                    uiStateFlow.update {
                        DownloadManagementScreenUiState(
                            downloads = items,
                            callbacks = callbacks,
                        )
                    }
                }
        }
    }.asStateFlow()

    /**
     * ダウンロード完了ファイルのサムネイルを非同期で読み込む。
     * MediaStore がサムネイルを生成できないファイル (zip 等) は null として記録する。
     */
    private fun requestThumbnail(fileUri: String) {
        if (!requestedThumbnailUris.add(fileUri)) return
        viewModelScope.launch(Dispatchers.IO) {
            val thumbnail = runCatching {
                getApplication<Application>().contentResolver.loadThumbnail(
                    fileUri.toUri(),
                    Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX),
                    null,
                )
            }.getOrNull()?.asImageBitmap()
            thumbnailFlow.update { it + (fileUri to thumbnail) }
        }
    }

    private fun buildCallbacks() = DownloadManagementScreenUiState.Callbacks(
        onCancel = { id -> cancelDownload(id) },
        onPause = { id -> pauseDownload(id) },
        onOpenFile = { fileUri -> openFile(fileUri) },
        onOpenDownloadsFolder = { openDownloadsFolder() },
        onResume = { id -> resumeDownload(id) },
        onOpenOriginPage = { url -> eventHandler.trySend { it.navigateToUrl(url) } },
    )

    private fun DownloadRecord.toDownloadItem(
        thumbnails: Map<String, ImageBitmap?>,
        pausingIds: Set<UUID>,
    ): DownloadManagementScreenUiState.DownloadItem {
        val uiStatus = when (status) {
            DownloadRecordStatus.SUCCEEDED -> {
                val uri = fileUri
                if (uri != null) {
                    DownloadManagementScreenUiState.DownloadStatus.Completed(
                        fileUri = uri,
                        thumbnail = thumbnails[uri],
                    )
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
            DownloadRecordStatus.PAUSED -> {
                if (workerId in pausingIds) {
                    // ワーカーがまだ停止処理中: グレーアウト + 円形インジケーターを表示する
                    DownloadManagementScreenUiState.DownloadStatus.Pausing(
                        progress = progress,
                        totalRead = totalRead,
                        contentLength = contentLength,
                    )
                } else {
                    DownloadManagementScreenUiState.DownloadStatus.Paused(
                        progress = progress,
                        totalRead = totalRead,
                        contentLength = contentLength,
                    )
                }
            }
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
        val record = currentRecords.find { it.workerId == id } ?: return
        // WorkManager・通知・DB はいずれも現在のワーカーID（currentWorkerId）で識別する
        val currentWorkerId = record.currentWorkerId
        // 一時停止中のレコードは Worker が存在しないため、残った部分ファイルをここで削除する
        val pausedPartialFileUri = record.partialFileUri.takeIf { record.status == DownloadRecordStatus.PAUSED }
        // 停止処理中にキャンセルされた場合はフラグを解除する
        pausingIds.update { it - id }
        // suspend を挟むと viewModelScope の破棄でキャンセル要求自体が消えるため、即時に発行する
        workManager.cancelWorkById(currentWorkerId)
        // Worker 起動前に GeckoDownloadManager が直接表示した通知は誰も消さないため、
        // 同じ導出式（workId の hashCode）で通知 ID を求めて明示的に消す
        val notificationId = currentWorkerId.hashCode() and 0x7fffffff
        getApplication<Application>()
            .getSystemService(NotificationManager::class.java)
            ?.cancel(notificationId)
        viewModelScope.launch {
            // Worker の CancellationException ハンドラに依存せず無条件で CANCELLED に更新する。
            // SUCCEEDED/FAILED の上書きは DAO 側のガードで防がれる。
            // WorkManager の割り込みが届かない場合でも、Worker が進捗更新時に
            // この CANCELLED 状態を検知して自力で停止する
            downloadRepository.updateCancelled(currentWorkerId.toString())
            pausedPartialFileUri?.let { uri ->
                runCatching {
                    getApplication<Application>().contentResolver.delete(uri.toUri(), null, null)
                }
            }
        }
    }

    /**
     * 実行中のダウンロードを一時停止する。
     * 先に DB を PAUSED に更新してから WorkManager に割り込みを発行することで、
     * Worker の CancellationException ハンドラが一時停止を検知して部分ファイルを保持する
     */
    private fun pauseDownload(id: UUID) {
        val record = currentRecords.find { it.workerId == id } ?: return
        val currentWorkerId = record.currentWorkerId
        // 停止処理中フラグを立てる（UI 上はグレーアウト + 円形インジケーター）
        pausingIds.update { it + id }
        viewModelScope.launch {
            downloadRepository.updatePaused(currentWorkerId.toString())
            workManager.cancelWorkById(currentWorkerId)
            // Worker 起動前に GeckoDownloadManager が直接表示した通知は誰も消さないため、
            // 同じ導出式（workId の hashCode）で通知 ID を求めて明示的に消す
            val notificationId = currentWorkerId.hashCode() and 0x7fffffff
            getApplication<Application>()
                .getSystemService(NotificationManager::class.java)
                ?.cancel(notificationId)
            // ワーカーが実際に停止（部分ファイル保存）し終えるまで待ってからフラグを解除する。
            // WorkInfo が終端状態になった時点でワーカーの後処理は完了している
            workManager.getWorkInfoByIdFlow(currentWorkerId)
                .first { it == null || it.state.isFinished }
            pausingIds.update { it - id }
        }
    }

    /**
     * 失敗または一時停止したダウンロードを再開する。
     * 既存レコードを付け替えるため、リスト上の位置とアイテム同一性は維持される。
     * 部分ファイルが残っている場合はRangeリクエストで続きから、
     * 無い場合はURLを再取得して最初からダウンロードする。
     */
    private fun resumeDownload(id: UUID) {
        val record = currentRecords.find { it.workerId == id } ?: return
        if (record.status != DownloadRecordStatus.FAILED &&
            record.status != DownloadRecordStatus.PAUSED
        ) {
            return
        }
        geckoDownloadManager.resumeDownload(
            workerId = record.workerId.toString(),
            url = record.url,
            referrerUrl = record.referrerUrl,
            partialFileUri = record.partialFileUri,
            totalRead = record.totalRead,
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

    companion object {
        /** loadThumbnail に渡すサムネイルの最大サイズ (px) */
        private const val THUMBNAIL_SIZE_PX = 256
    }
}

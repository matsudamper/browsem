package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.DownloadNotificationId
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.data.download.DownloadRecord
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.download.DownloadThumbnail
import net.matsudamper.browser.download.DownloadThumbnailLoader
import net.matsudamper.browser.download.DownloadUrl
import net.matsudamper.browser.ui.downloads.DownloadManagementScreenUiState

internal class DownloadManagementScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    private val downloadRepository = DownloadRepository(application)
    private val geckoDownloadManager = GeckoDownloadManager(application, downloadRepository)
    private val showClearHistoryDialog = MutableStateFlow(false)
    private val screenCallbacks = object : DownloadManagementScreenUiState.Callbacks {
        override fun onOpenDownloadsFolder() {
            openDownloadsFolder()
        }

        override fun onClickClearHistory() {
            showClearHistoryDialog.value = true
        }

        override fun onConfirmClearHistory() {
            viewModelScope.launch {
                clearDownloadHistory()
                showClearHistoryDialog.value = false
            }
        }

        override fun onDismissClearHistoryDialog() {
            showClearHistoryDialog.value = false
        }

        override suspend fun loadPreview(fileUri: String): DownloadManagementScreenUiState.Preview {
            return this@DownloadManagementScreenViewModel.loadPreview(fileUri)
        }
    }

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    /** resumeDownload から最新のレコードを参照するためのキャッシュ */
    private var currentRecords: List<DownloadRecord> = emptyList()

    val uiState: StateFlow<DownloadManagementScreenUiState> = MutableStateFlow(
        DownloadManagementScreenUiState(
            isLoading = true,
            downloads = emptyList(),
            hasClearableHistory = false,
            showClearHistoryDialog = false,
            callbacks = screenCallbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            combine(
                downloadRepository.observeDownloads(),
                showClearHistoryDialog,
            ) { records, showDialog ->
                records to showDialog
            }.collectLatest { (records, showDialog) ->
                if (reconcileInactiveWorkRecords(records)) {
                    return@collectLatest
                }
                currentRecords = records
                val items = records.map { record -> record.toDownloadItem() }
                val hasClearableHistory =
                    records.any { it.status !in ACTIVE_DOWNLOAD_STATUSES } &&
                        records.none { it.status in ACTIVE_DOWNLOAD_STATUSES }
                uiStateFlow.update {
                    DownloadManagementScreenUiState(
                        isLoading = false,
                        downloads = items,
                        hasClearableHistory = hasClearableHistory,
                        showClearHistoryDialog = showDialog,
                        callbacks = screenCallbacks,
                    )
                }
            }
        }
    }.asStateFlow()

    /**
     * ダウンロード済みファイルのプレビューを読み込む。
     * MediaStore がサムネイルを生成できる画像・動画・音声はサムネイルを、
     * APK は PackageManager で取り出したアプリアイコンを返す。
     * どちらも取得できない場合は MIME タイプから判定した汎用アイコンを返す
     */
    private suspend fun loadPreview(fileUri: String): DownloadManagementScreenUiState.Preview {
        return withContext(Dispatchers.IO) {
            when (val thumbnail = DownloadThumbnailLoader.load(getApplication(), fileUri, PREVIEW_SIZE_PX)) {
                is DownloadThumbnail.Thumbnail -> {
                    DownloadManagementScreenUiState.Preview.Thumbnail(thumbnail.bitmap.asImageBitmap())
                }

                is DownloadThumbnail.AppIcon -> {
                    DownloadManagementScreenUiState.Preview.AppIcon(thumbnail.bitmap.asImageBitmap())
                }

                null -> {
                    val uri = fileUri.toUri()
                    DownloadManagementScreenUiState.Preview.FileType(toDownloadFileType(getMimeType(uri), getDisplayName(uri)))
                }
            }
        }
    }

    private fun isApk(mimeType: String?, fileName: String?): Boolean {
        if (mimeType.equals(MIME_TYPE_APK, ignoreCase = true)) return true
        return fileName?.endsWith(".apk", ignoreCase = true) == true
    }

    /**
     * MIME タイプ（取得できない場合は拡張子）からファイル種別を判定する。
     * MediaStore は拡張子を認識できないファイルに application/octet-stream を返すため、
     * MIME タイプで判定できない場合は拡張子でも判定する
     */
    private fun toDownloadFileType(
        mimeType: String?,
        fileName: String?,
    ): DownloadManagementScreenUiState.DownloadFileType {
        val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
        when {
            normalizedMimeType == null -> Unit

            normalizedMimeType.startsWith("video/") -> {
                return DownloadManagementScreenUiState.DownloadFileType.VIDEO
            }

            normalizedMimeType.startsWith("audio/") -> {
                return DownloadManagementScreenUiState.DownloadFileType.AUDIO
            }

            normalizedMimeType == MIME_TYPE_PDF -> return DownloadManagementScreenUiState.DownloadFileType.PDF

            normalizedMimeType in ARCHIVE_MIME_TYPES -> {
                return DownloadManagementScreenUiState.DownloadFileType.ARCHIVE
            }
        }
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return when {
            extension.isNullOrEmpty() -> DownloadManagementScreenUiState.DownloadFileType.UNKNOWN
            extension in ARCHIVE_EXTENSIONS -> DownloadManagementScreenUiState.DownloadFileType.ARCHIVE
            extension == "pdf" -> DownloadManagementScreenUiState.DownloadFileType.PDF
            else -> DownloadManagementScreenUiState.DownloadFileType.UNKNOWN
        }
    }

    private fun getMimeType(uri: Uri): String? {
        return runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull()
    }

    /**
     * MediaStore の content:// URI では lastPathSegment が数値IDになるため、
     * 拡張子を見るには DISPLAY_NAME を取得する必要がある
     */
    private fun getDisplayName(uri: Uri): String? {
        return runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun DownloadRecord.toDownloadItem(): DownloadManagementScreenUiState.DownloadItem {
        val uiStatus = when (status) {
            DownloadRecordStatus.SUCCEEDED -> {
                val uri = fileUri
                if (uri != null) {
                    DownloadManagementScreenUiState.DownloadStatus.Completed(
                        fileUri = uri,
                    )
                } else {
                    DownloadManagementScreenUiState.DownloadStatus.Failed(
                        canResume = false,
                        reason = failureReason,
                    )
                }
            }

            DownloadRecordStatus.FAILED -> {
                DownloadManagementScreenUiState.DownloadStatus.Failed(
                    // blob: URL は再取得できないため、部分ファイルがあっても再開ボタンを出さない
                    canResume = partialFileUri != null && DownloadUrl.isRefetchable(url),
                    reason = failureReason,
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
                DownloadManagementScreenUiState.DownloadStatus.Paused(
                    progress = progress,
                    totalRead = totalRead,
                    contentLength = contentLength,
                    // blob: URL は再取得できないため、部分ファイルがあっても再開ボタンを出さない
                    canResume = DownloadUrl.isRefetchable(url),
                )
            }
        }
        return DownloadManagementScreenUiState.DownloadItem(
            id = workerId,
            fileName = fileName.ifEmpty {
                if (status == DownloadRecordStatus.FAILED) "ダウンロード失敗" else "ダウンロード中..."
            },
            status = uiStatus,
            enqueuedAt = enqueuedAt,
            originPageUrl = referrerUrl.ifBlank { null },
            listener = object : DownloadManagementScreenUiState.DownloadItem.Listener {
                override fun onCancel() {
                    cancelDownload(workerId)
                }

                override fun onPause() {
                    pauseDownload(workerId)
                }

                override fun onOpenFile() {
                    val completedUri = (uiStatus as? DownloadManagementScreenUiState.DownloadStatus.Completed)?.fileUri
                    if (completedUri != null) {
                        openFile(completedUri)
                    }
                }

                override fun onResume() {
                    resumeDownload(workerId)
                }

                override fun onOpenOriginPage() {
                    referrerUrl.takeIf { it.isNotBlank() }?.let { url ->
                        eventHandler.trySend { it.navigateToUrl(url) }
                    }
                }
            },
        )
    }

    private fun cancelDownload(id: UUID) {
        val record = currentRecords.find { it.workerId == id } ?: return
        // WorkManager・通知・DB はいずれも現在のワーカーID（currentWorkerId）で識別する
        val currentWorkerId = record.currentWorkerId
        // 一時停止中のレコードは Worker が存在しないため、残った部分ファイルをここで削除する
        val pausedPartialFileUri = record.partialFileUri.takeIf { record.status == DownloadRecordStatus.PAUSED }
        // suspend を挟むと viewModelScope の破棄でキャンセル要求自体が消えるため、即時に発行する
        workManager.cancelWorkById(currentWorkerId)
        // Worker 起動前に GeckoDownloadManager が直接表示した通知は誰も消さないため、明示的に消す
        val notificationId = DownloadNotificationId.progress(currentWorkerId)
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
        viewModelScope.launch {
            downloadRepository.updatePaused(currentWorkerId.toString())
            workManager.cancelWorkById(currentWorkerId)
            // Worker 起動前に GeckoDownloadManager が直接表示した通知は誰も消さないため、明示的に消す
            val notificationId = DownloadNotificationId.progress(currentWorkerId)
            getApplication<Application>()
                .getSystemService(NotificationManager::class.java)
                ?.cancel(notificationId)
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
        val canReadFile = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!canReadFile) {
            Toast.makeText(app, "ファイルがありません", Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = getMimeType(uri) ?: "*/*"
        // MIME タイプが APK でなくても拡張子で判定できるようファイル名も見る
        val isApk = isApk(mimeType, getDisplayName(uri))

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
        val effectiveMimeType = if (isApk) MIME_TYPE_APK else mimeType
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

    /**
     * WorkManager 上で終了済みの ENQUEUED/RUNNING レコードを CANCELLED に同期する。
     * バックアップ開始時の一括取消などで Worker が起動せず DB だけ残るケースを解消する。
     *
     * @return DB を更新した場合は true。呼び出し元は更新後の Flow 再発火を待つこと。
     */
    private suspend fun reconcileInactiveWorkRecords(records: List<DownloadRecord>): Boolean {
        var updated = false
        for (record in records) {
            if (record.status !in ACTIVE_DOWNLOAD_STATUSES) continue
            val workState = runCatching {
                workManager.getWorkInfoById(record.currentWorkerId).get()?.state
            }.getOrNull() ?: WorkInfo.State.CANCELLED
            if (workState.isFinished) {
                downloadRepository.updateCancelled(record.currentWorkerId.toString())
                updated = true
            }
        }
        return updated
    }

    private suspend fun clearDownloadHistory() {
        withContext(Dispatchers.IO) {
            var records = downloadRepository.getAllDownloads()
            if (reconcileInactiveWorkRecords(records)) {
                records = downloadRepository.getAllDownloads()
            }
            deletePartialFilesForClearableRecords(records)
            downloadRepository.clearHistory()
        }
    }

    /** 履歴削除対象（PAUSED/FAILED 等）レコードに紐づく未完了の部分ファイルを破棄する */
    private fun deletePartialFilesForClearableRecords(records: List<DownloadRecord>) {
        val resolver = getApplication<Application>().contentResolver
        records
            .filter { it.status !in ACTIVE_DOWNLOAD_STATUSES }
            .mapNotNull { it.partialFileUri }
            .forEach { partialUri ->
                runCatching {
                    resolver.delete(partialUri.toUri(), null, null)
                }
            }
    }

    interface Event {
        /** ダウンロード開始時のページURLを新しいタブで開く */
        fun navigateToUrl(url: String)

        /** 指定アイテムへスクロールしてハイライト点滅させる */
        fun highlightItem(id: UUID)
    }

    fun requestHighlight(id: UUID) {
        eventHandler.trySend { it.highlightItem(id) }
    }

    companion object {
        /** 履歴クリアの対象外。実行中のダウンロードは残す */
        private val ACTIVE_DOWNLOAD_STATUSES = setOf(
            DownloadRecordStatus.ENQUEUED,
            DownloadRecordStatus.RUNNING,
        )

        /** サムネイル・アプリアイコンを読み込む際の最大サイズ (px) */
        private const val PREVIEW_SIZE_PX = 256

        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

        private const val MIME_TYPE_PDF = "application/pdf"

        private val ARCHIVE_MIME_TYPES = setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/gzip",
            "application/x-gzip",
            "application/x-tar",
            "application/x-compressed-tar",
            "application/x-bzip",
            "application/x-bzip2",
            "application/x-xz",
            "application/zstd",
            "application/x-7z-compressed",
            "application/vnd.rar",
            "application/x-rar-compressed",
            "application/x-lzh-compressed",
            "application/java-archive",
        )

        /**
         * 圧縮アーカイブとして扱う拡張子。
         * MediaStore が application/octet-stream しか返さない場合の判定に使う
         */
        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "gz", "tgz", "tar", "bz2", "tbz2", "xz", "zst", "7z", "rar", "lzh", "jar",
        )
    }
}

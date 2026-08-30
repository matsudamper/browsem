package net.matsudamper.browser.screen.downloads

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.data.download.DownloadRecord
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.download.DownloadUrl
import net.matsudamper.browser.ui.downloads.DownloadManagementScreenUiState

internal class DownloadManagementScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    private val downloadRepository = DownloadRepository(application)
    private val geckoDownloadManager = GeckoDownloadManager(application, downloadRepository)
    private val screenCallbacks = object : DownloadManagementScreenUiState.Callbacks {
        override fun onOpenDownloadsFolder() {
            openDownloadsFolder()
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
            callbacks = screenCallbacks,
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            downloadRepository.observeDownloads()
                .collectLatest { records ->
                    currentRecords = records
                    val items = records.map { record -> record.toDownloadItem() }
                    uiStateFlow.update {
                        DownloadManagementScreenUiState(
                            isLoading = false,
                            downloads = items,
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
            val uri = fileUri.toUri()
            val thumbnail = runCatching {
                getApplication<Application>().contentResolver.loadThumbnail(
                    uri,
                    Size(PREVIEW_SIZE_PX, PREVIEW_SIZE_PX),
                    null,
                )
            }.getOrNull()
            if (thumbnail != null) {
                return@withContext DownloadManagementScreenUiState.Preview.Thumbnail(thumbnail.asImageBitmap())
            }
            val mimeType = getMimeType(uri)
            val fileName = getDisplayName(uri)
            if (isApk(mimeType, fileName)) {
                loadApkIcon(uri)?.let {
                    return@withContext DownloadManagementScreenUiState.Preview.AppIcon(it)
                }
            }
            DownloadManagementScreenUiState.Preview.FileType(toDownloadFileType(mimeType, fileName))
        }
    }

    /**
     * APK ファイルからアプリアイコンを取り出す。解析に失敗した場合は null を返す。
     * PackageManager.getPackageArchiveInfo はファイルパスしか受け付けないため、
     * MediaStore の content:// URI を実ファイルパスに解決してから渡す
     */
    private fun loadApkIcon(uri: Uri): ImageBitmap? {
        val packageManager = getApplication<Application>().packageManager
        return useFilePath(uri) { path ->
            val packageInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageArchiveInfo(path, 0)
                }
            }.getOrNull() ?: return@useFilePath null
            val applicationInfo = packageInfo.applicationInfo ?: return@useFilePath null
            // アイコンのリソースを APK 自身から解決させるため、参照先パスを設定する
            applicationInfo.sourceDir = path
            applicationInfo.publicSourceDir = path
            runCatching {
                applicationInfo.loadIcon(packageManager)
                    .toBitmap(width = PREVIEW_SIZE_PX, height = PREVIEW_SIZE_PX)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    /** MIME タイプまたは拡張子から APK かどうかを判定する */
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

    /**
     * content:// URI を、ファイルパスを要求する API へ渡せる形に解決して [block] を呼ぶ。
     * MediaStore の DATA 列が使える場合はその実パスを、使えない場合は
     * ファイルディスクリプタ経由の /proc/self/fd パスを渡す。
     * 後者は [block] の実行中のみ有効なため、[block] 内で読み切る必要がある
     */
    private fun <T> useFilePath(uri: Uri, block: (path: String) -> T?): T? {
        val resolver = getApplication<Application>().contentResolver
        val dataPath = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (dataPath != null && File(dataPath).canRead()) {
            return runCatching { block(dataPath) }.getOrNull()
        }
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                block("/proc/self/fd/${descriptor.fd}")
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
                // FAILEDかつファイル名未取得の場合は失敗を明示する
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
        viewModelScope.launch {
            downloadRepository.updatePaused(currentWorkerId.toString())
            workManager.cancelWorkById(currentWorkerId)
            // Worker 起動前に GeckoDownloadManager が直接表示した通知は誰も消さないため、
            // 同じ導出式（workId の hashCode）で通知 ID を求めて明示的に消す
            val notificationId = currentWorkerId.hashCode() and 0x7fffffff
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
        /** サムネイル・アプリアイコンを読み込む際の最大サイズ (px) */
        private const val PREVIEW_SIZE_PX = 256

        /** APK の MIME タイプ */
        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

        private const val MIME_TYPE_PDF = "application/pdf"

        /** 圧縮アーカイブとして扱う MIME タイプ */
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

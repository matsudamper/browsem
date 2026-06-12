package net.matsudamper.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.download.DownloadEngine
import net.matsudamper.browser.download.DownloadHttpClient
import net.matsudamper.browser.download.DownloadHttpResponse
import net.matsudamper.browser.download.DownloadMetadata
import net.matsudamper.browser.download.GeckoDownloadHttpClient
import net.matsudamper.browser.download.PendingDownloadBodyStore
import net.matsudamper.browser.download.WebResponseDownloadResponse
import org.mozilla.geckoview.GeckoRuntime
import java.io.IOException

/**
 * WorkManagerを使った進捗通知付きダウンロードWorker。
 * GeckoWebExecutorを使用してダウンロードすることで、GeckoViewのCookie/セッション情報が共有される。
 * 進捗・結果はRoomに書き込み、WorkManagerのprogress/outputDataは使用しない。
 * partialFileUri が指定された場合はHTTP Rangeリクエストを使って中断箇所から再開する。
 */
internal class DownloadWorker(
    private val context: Context,
    params: WorkerParameters,
    geckoRuntime: GeckoRuntime,
) : CoroutineWorker(context, params) {
    private val repository get() = DownloadRepository(context)

    /** HTTP取得のクライアント。GeckoViewのCookie/セッションを共有する */
    private val httpClient: DownloadHttpClient = GeckoDownloadHttpClient(geckoRuntime)

    /** ストリームコピー・切断検出を担うコアロジック */
    private val engine = DownloadEngine()

    /** 失敗時に保存した部分ファイルURI（doWork終了後にcatchブロックで参照） */
    private var partialResultUri: Uri? = null
    private var partialResultFileName: String = ""
    private var partialResultTotalRead: Long = 0L
    private var partialResultContentLength: Long = -1L

    /**
     * 元レスポンス（pendingResponse）のボディを直接保存したか。
     * この場合は URL 再取得での再開が無効データ（ワンタイムURL無効化・ログインページ等）になり得るため、
     * 失敗時に再開可能として保存しない。
     */
    private var usedPendingBody: Boolean = false

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val referrerUrl = inputData.getString(KEY_REFERRER_URL).orEmpty()
        // inputDataから通知IDを読み出す（GeckoDownloadManagerと共有）
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, NOTIFICATION_ID)
        // 再開モード: 部分ファイルURI
        val partialFileUriString = inputData.getString(KEY_PARTIAL_FILE_URI)


        val enqueuedAt = System.currentTimeMillis()

        // URLからファイル名を推測して最初から保存しておく
        val guessedFileName = URLUtil.guessFileName(url, null, null)

        ensureNotificationChannel(context)
        setForeground(createForegroundInfo(notificationId, 0, true, context.getString(R.string.download_notification_starting), 0L, -1L))

        repository.insertDownload(workerId = id.toString(), url = url, referrerUrl = referrerUrl, enqueuedAt = enqueuedAt)

        return try {
            // エンキュー直後にキャンセルされた場合（WorkManager 登録前のキャンセル等で
            // 割り込みが届かず Worker が起動してしまったケース）はダウンロードを開始しない
            throwIfCancelledOnRecord()
            val (fileUri, fileName) = if (partialFileUriString != null) {
                downloadFileResume(
                    urlString = url,
                    referrerUrl = referrerUrl,
                    notificationId = notificationId,
                    repository = repository,
                    partialUri = Uri.parse(partialFileUriString),
                )
            } else {
                downloadFile(url, referrerUrl, notificationId, repository)
            }
            repository.updateCompleted(id.toString(), fileName, fileUri.toString())
            postCompletionNotification(fileName)
            Result.success()
        } catch (e: CancellationException) {
            // Job キャンセル済みのコルーチン上では Room の suspend クエリが即座に
            // CancellationException を投げて DB 更新・ファイル削除がスキップされるため、
            // NonCancellable で囲んで確実に実行する
            withContext(NonCancellable) {
                val savedUri = partialResultUri
                if (repository.isPaused(id.toString())) {
                    if (savedUri != null && partialResultTotalRead > 0 && !usedPendingBody) {
                        // 一時停止: 部分ファイルを保持して再開（HTTP Range）に備える
                        repository.updatePausedPartial(
                            workerId = id.toString(),
                            partialFileUri = savedUri.toString(),
                            fileName = partialResultFileName,
                            totalRead = partialResultTotalRead,
                            contentLength = partialResultContentLength,
                        )
                    } else {
                        // 部分ファイルが再開に使えない場合は削除する。
                        // PAUSED 状態は維持され、再開時は URL を再取得してダウンロードし直す
                        savedUri?.let { context.contentResolver.delete(it, null, null) }
                    }
                } else {
                    repository.updateCancelled(id.toString())
                    // キャンセル時は部分ファイルを削除する
                    savedUri?.let { context.contentResolver.delete(it, null, null) }
                }
            }
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            val savedUri = partialResultUri
            if (savedUri != null && partialResultTotalRead > 0 && !usedPendingBody) {
                // 部分ファイルが存在する場合は再開可能として保存する
                repository.updatePartialFailed(
                    workerId = id.toString(),
                    partialFileUri = savedUri.toString(),
                    fileName = partialResultFileName,
                    totalRead = partialResultTotalRead,
                    contentLength = partialResultContentLength,
                )
            } else {
                // partialResultUri が非null かつ 0バイトの場合は孤立したMediaStoreエントリを削除する
                savedUri?.let { context.contentResolver.delete(it, null, null) }
                repository.updateFailed(id.toString())
            }
            Result.failure()
        }
    }

    /**
     * Room のレコードがキャンセル済みなら CancellationException を投げてダウンロードを中断する。
     * 管理画面のキャンセルは WorkManager の割り込みに依存せず無条件で DB を CANCELLED に
     * 更新するため、割り込みが届かないケース（WorkManager 登録前のキャンセル等）でも
     * Worker がこのチェックによって自力で停止できる
     */
    private suspend fun throwIfCancelledOnRecord() {
        if (repository.isStopRequested(id.toString())) {
            throw CancellationException("ダウンロードがキャンセルまたは一時停止されました")
        }
    }

    private fun postCompletionNotification(fileName: String) {
        // 負のhashCodeによる通知ID衝突を防ぐため、非負の値に変換する
        val positiveHash = id.hashCode() and 0x7fffffff
        val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            positiveHash,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText(context.getString(R.string.download_notification_complete))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_COMPLETE_BASE + positiveHash, notification)
    }

    private suspend fun postFailureNotification() {
        // フォアグラウンド通知と異なるIDを使う。
        // フォアグラウンド通知と同じIDを使うと、WorkManager がフォアグラウンドサービス停止時に
        // stopForeground(STOP_FOREGROUND_REMOVE) で同IDの通知を削除してしまうため。
        val positiveHash = id.hashCode() and 0x7fffffff
        val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            positiveHash,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fileName = repository.get(id).fileName

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(fileName)
            .setContentText(context.getString(R.string.download_notification_failed))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_FAILURE_BASE + positiveHash, notification)
    }

    private suspend fun downloadFile(
        urlString: String,
        referrerUrl: String,
        notificationId: Int,
        repository: DownloadRepository,
    ): Pair<Uri, String> {
        // onExternalResponse で保持した元レスポンスがあれば、そのボディを直接保存する。
        // パスワード submit(POST)・ワンタイムURL・セッション依存のダウンロードは
        // URL を GET し直すと 0 バイトになるため、元レスポンスのボディを優先する。
        val pendingResponse = PendingDownloadBodyStore.take(id.toString())
        // 元レスポンスのボディを使う場合、URL再取得での再開は無効データになり得るため記録する
        usedPendingBody = pendingResponse != null
        val response: DownloadHttpResponse = pendingResponse?.let { WebResponseDownloadResponse(it) }
            ?: httpClient.fetch(urlString, referrerUrl, 0L)

        try {
            val statusCode = response.statusCode
            if (statusCode !in 200 until 300) {
                throw IOException("HTTP エラー: $statusCode")
            }

            val body = response.body ?: throw IOException("レスポンスボディが空です。")
            val contentLength = DownloadMetadata.parseContentLength(response.header("Content-Length"))
            val mimeType = DownloadMetadata.parseMimeType(response.header("Content-Type"))
            val fileName = URLUtil.guessFileName(urlString, response.header("Content-Disposition"), mimeType)
                .ifBlank { "download-${System.currentTimeMillis()}" }

            setForeground(createForegroundInfo(notificationId, 0, contentLength <= 0, fileName, 0L, contentLength))
            repository.updateProgress(id.toString(), fileName, 0, 0L, contentLength)

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("ダウンロードエントリの作成に失敗しました。")

            // 失敗時に部分ファイルURIを参照できるよう保存する
            partialResultUri = uri
            partialResultFileName = fileName
            partialResultContentLength = contentLength

            var lastUpdateTime = 0L
            resolver.openOutputStream(uri)?.use { outputStream ->
                engine.copyTo(
                    body = body,
                    sink = outputStream,
                    expectedTotalLength = contentLength,
                    startBytes = 0L,
                ) { totalRead ->
                    // 失敗時の部分ファイルサイズ把握のため累計バイト数は毎回更新する
                    partialResultTotalRead = totalRead
                    // 通知・Room更新のみレート制限する
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL_MILLIS) {
                        // WorkManager の割り込みが取りこぼされても確実に停止できるよう、
                        // DB のキャンセル状態を確認して自力で中断する
                        throwIfCancelledOnRecord()
                        val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else 0
                        repository.updateProgress(id.toString(), fileName, progress, totalRead, contentLength)
                        setForeground(createForegroundInfo(notificationId, progress, contentLength <= 0, fileName, totalRead, contentLength))
                        lastUpdateTime = now
                    }
                }
            } ?: throw IOException("出力ストリームを開けませんでした。")

            val completeValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, completeValues, null, null)
            // 完了したので部分ファイル情報をクリアする
            partialResultUri = null
            // IS_PENDING=0 更新後にMediaStoreが重複を避けてリネームした場合に備え、実際のファイル名を取得する
            val actualFileName = resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            return Pair(uri, actualFileName ?: fileName)
        } finally {
            // ボディを確実にクローズする（pendingResponse 経由・fetch 経由いずれの場合も）
            response.close()
        }
    }

    /**
     * HTTP Rangeリクエストを使って中断箇所からダウンロードを再開する。
     * サーバーが206 Partial Contentを返した場合のみ再開し、200 OKの場合は最初からやり直す。
     */
    private suspend fun downloadFileResume(
        urlString: String,
        referrerUrl: String,
        notificationId: Int,
        repository: DownloadRepository,
        partialUri: Uri,
    ): Pair<Uri, String> {
        val resolver = context.contentResolver

        // 部分ファイルの実際のサイズを取得する（DBの値と一致しない場合に備える）
        val actualFileSize = resolver.openFileDescriptor(partialUri, "r")?.use { it.statSize } ?: 0L
        val rangeStart = actualFileSize

        val response = httpClient.fetch(urlString, referrerUrl, rangeStart)
        try {
            val statusCode = response.statusCode

            // サーバーがRangeリクエストをサポートしていない場合（200 OK）は最初からやり直す
            if (statusCode == 200) {
                // 部分ファイルを削除して新規ダウンロードを開始する
                resolver.delete(partialUri, null, null)
                partialResultUri = null
                return downloadFile(urlString, referrerUrl, notificationId, repository)
            }

            if (statusCode != 206) {
                throw IOException("HTTP エラー: $statusCode")
            }

            // 206 Partial Content: 既存ファイルへ追記する
            val body = response.body ?: throw IOException("レスポンスボディが空です。")
            val contentRangeHeader = response.header("Content-Range")
            // Content-Range: bytes START-END/TOTAL 形式からトータルサイズを取得する
            val totalFileSize = DownloadMetadata.parseTotalFromContentRange(contentRangeHeader)
                ?: (rangeStart + DownloadMetadata.parseContentLength(response.header("Content-Length")))
            val contentLength = totalFileSize
            val mimeType = DownloadMetadata.parseMimeType(response.header("Content-Type"))
            val fileName = URLUtil.guessFileName(urlString, response.header("Content-Disposition"), mimeType)
                .ifBlank { "download-${System.currentTimeMillis()}" }

            setForeground(createForegroundInfo(notificationId, if (contentLength > 0) (rangeStart * 100 / contentLength).toInt() else 0, contentLength <= 0, fileName, rangeStart, contentLength))

            // 部分ファイルへの追記用に IS_PENDING を確認・維持する
            val pendingValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }
            resolver.update(partialUri, pendingValues, null, null)

            partialResultUri = partialUri
            partialResultFileName = fileName
            partialResultContentLength = contentLength
            partialResultTotalRead = rangeStart

            var lastUpdateTime = 0L
            // "wa" モードで追記オープンする
            resolver.openOutputStream(partialUri, "wa")?.use { outputStream ->
                engine.copyTo(
                    body = body,
                    sink = outputStream,
                    expectedTotalLength = contentLength,
                    startBytes = rangeStart,
                ) { totalRead ->
                    partialResultTotalRead = totalRead
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL_MILLIS) {
                        // WorkManager の割り込みが取りこぼされても確実に停止できるよう、
                        // DB のキャンセル状態を確認して自力で中断する
                        throwIfCancelledOnRecord()
                        val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else 0
                        repository.updateProgress(id.toString(), fileName, progress, totalRead, contentLength)
                        setForeground(createForegroundInfo(notificationId, progress, contentLength <= 0, fileName, totalRead, contentLength))
                        lastUpdateTime = now
                    }
                }
            } ?: throw IOException("出力ストリームを開けませんでした。")

            val completeValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(partialUri, completeValues, null, null)
            partialResultUri = null
            return Pair(partialUri, fileName)
        } finally {
            response.close()
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        progress: Int,
        indeterminate: Boolean,
        title: String,
        totalRead: Long,
        contentLength: Long,
    ): ForegroundInfo {
        val sizeText = buildSizeText(totalRead, contentLength)
        // タップ時にダウンロード管理画面を開くPendingIntent
        val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sizeText)
            .setProgress(100, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
        return ForegroundInfo(
            notificationId,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        /** 進捗（通知・Room）の更新間隔。頻繁な更新を避けるためのレート制限 */
        private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 1000L

        const val KEY_URL = "url"
        const val KEY_REFERRER_URL = "referrer_url"
        const val KEY_NOTIFICATION_ID = "notification_id"
        /** 再開モード: 部分ファイルのMediaStore URI */
        const val KEY_PARTIAL_FILE_URI = "partial_file_uri"
        /** 再開モード: 再開を開始するバイト位置 */
        const val KEY_RESUME_FROM_BYTES = "resume_from_bytes"
        const val CHANNEL_ID = "download_progress_channel"
        const val NOTIFICATION_ID = 9001

        /** 完了通知IDのベース。ワークIDのhashCodeを加算して使用する */
        const val NOTIFICATION_ID_COMPLETE_BASE = 10000

        /** 失敗通知IDのベース。ワークIDのhashCodeを加算して使用する */
        const val NOTIFICATION_ID_FAILURE_BASE = 20000
        const val TAG_DOWNLOAD = "download"

        /** ダウンロード管理画面を開くためのActionキー */
        const val ACTION_OPEN_DOWNLOADS = "net.matsudamper.browser.ACTION_OPEN_DOWNLOADS"

        /**
         * バイト数を適切な単位（B/KB/MB）の文字列に変換する。
         * contentLength > 0 の場合は「転送済み / 総サイズ」形式で返す。
         */
        fun buildSizeText(totalRead: Long, contentLength: Long): String {
            return if (contentLength > 0) {
                "${formatBytes(totalRead)} / ${formatBytes(contentLength)}"
            } else {
                formatBytes(totalRead)
            }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        fun ensureNotificationChannel(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ダウンロード",
                    NotificationManager.IMPORTANCE_LOW,
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}

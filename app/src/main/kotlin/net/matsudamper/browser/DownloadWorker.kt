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
import net.matsudamper.browser.data.download.DownloadRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
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
    private val geckoRuntime: GeckoRuntime,
) : CoroutineWorker(context, params) {
    private val repository get() = DownloadRepository(context)

    /** 失敗時に保存した部分ファイルURI（doWork終了後にcatchブロックで参照） */
    private var partialResultUri: Uri? = null
    private var partialResultFileName: String = ""
    private var partialResultTotalRead: Long = 0L
    private var partialResultContentLength: Long = -1L

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
            repository.updateCancelled(id.toString())
            // キャンセル時は部分ファイルを削除する
            partialResultUri?.let { context.contentResolver.delete(it, null, null) }
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            val savedUri = partialResultUri
            if (savedUri != null && partialResultTotalRead > 0) {
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
        val executor = GeckoWebExecutor(geckoRuntime)
        val request = WebRequest.Builder(urlString)
            .apply { if (referrerUrl.isNotBlank()) addHeader("Referer", referrerUrl) }
            .build()

        val response = fetchResponse(executor, request)

        val statusCode = response.statusCode
        if (statusCode !in 200 until 300) {
            response.body?.close()
            throw IOException("HTTP エラー: $statusCode")
        }

        val body = response.body ?: throw IOException("レスポンスボディが空です。")
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        val mimeType = parseMimeType(response.headers["Content-Type"])
        val fileName = URLUtil.guessFileName(urlString, response.headers["Content-Disposition"], mimeType)
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

        resolver.openOutputStream(uri)?.use { outputStream ->
            body.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                // 通知・Room更新のレート制限を避けるため、最後に更新した時刻を記録する
                var lastUpdateTime = 0L
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    partialResultTotalRead = totalRead
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 1000L) {
                        val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else 0
                        repository.updateProgress(id.toString(), fileName, progress, totalRead, contentLength)
                        setForeground(createForegroundInfo(notificationId, progress, contentLength <= 0, fileName, totalRead, contentLength))
                        lastUpdateTime = now
                    }
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

        val executor = GeckoWebExecutor(geckoRuntime)

        val request = WebRequest.Builder(urlString)
            .apply {
                if (referrerUrl.isNotBlank()) addHeader("Referer", referrerUrl)
                if (rangeStart > 0) addHeader("Range", "bytes=$rangeStart-")
            }
            .build()

        val response = fetchResponse(executor, request)
        val statusCode = response.statusCode

        // サーバーがRangeリクエストをサポートしていない場合（200 OK）は最初からやり直す
        if (statusCode == 200) {
            response.body?.close()
            // 部分ファイルを削除して新規ダウンロードを開始する
            resolver.delete(partialUri, null, null)
            partialResultUri = null
            return downloadFile(urlString, referrerUrl, notificationId, repository)
        }

        if (statusCode != 206) {
            response.body?.close()
            throw IOException("HTTP エラー: $statusCode")
        }

        // 206 Partial Content: 既存ファイルへ追記する
        val body = response.body ?: throw IOException("レスポンスボディが空です。")
        val contentRangeHeader = response.headers["Content-Range"]
        // Content-Range: bytes START-END/TOTAL 形式からトータルサイズを取得する
        val totalFileSize = contentRangeHeader
            ?.substringAfter('/')?.toLongOrNull()
            ?: (rangeStart + (response.headers["Content-Length"]?.toLongOrNull() ?: -1L))
        val contentLength = totalFileSize
        val mimeType = parseMimeType(response.headers["Content-Type"])
        val fileName = URLUtil.guessFileName(urlString, response.headers["Content-Disposition"], mimeType)
            .ifBlank { "download-${System.currentTimeMillis()}" }

        setForeground(createForegroundInfo(notificationId, if (contentLength > 0) (rangeStart * 100 / contentLength).toInt() else 0, contentLength <= 0, fileName, rangeStart, contentLength))

        // 部分ファイルへの追記用に IS_PENDING を確認・維持する
        val pendingValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }
        resolver.update(partialUri, pendingValues, null, null)

        partialResultUri = partialUri
        partialResultFileName = fileName
        partialResultContentLength = contentLength
        partialResultTotalRead = rangeStart

        // "wa" モードで追記オープンする
        resolver.openOutputStream(partialUri, "wa")?.use { outputStream ->
            body.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = rangeStart
                var lastUpdateTime = 0L
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    partialResultTotalRead = totalRead
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 1000L) {
                        val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else 0
                        repository.updateProgress(id.toString(), fileName, progress, totalRead, contentLength)
                        setForeground(createForegroundInfo(notificationId, progress, contentLength <= 0, fileName, totalRead, contentLength))
                        lastUpdateTime = now
                    }
                }
            }
        } ?: throw IOException("出力ストリームを開けませんでした。")

        val completeValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(partialUri, completeValues, null, null)
        partialResultUri = null
        return Pair(partialUri, fileName)
    }

    /** GeckoWebExecutorでリクエストを送信してレスポンスを取得する */
    private fun fetchResponse(executor: GeckoWebExecutor, request: WebRequest): org.mozilla.geckoview.WebResponse {
        return try {
            executor.fetch(request).poll(60_000)
                ?: throw IOException("レスポンスがnullです。")
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("Geckoリクエスト失敗", e)
        }
    }

    /** Content-Typeヘッダーからメディアタイプ文字列を取り出す */
    private fun parseMimeType(contentType: String?): String {
        return contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"
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
            .setOngoing(true)
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

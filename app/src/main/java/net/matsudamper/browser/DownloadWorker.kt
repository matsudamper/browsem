package net.matsudamper.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.download.DownloadRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.IOException

/**
 * WorkManagerを使った進捗通知付きダウンロードWorker。
 * GeckoWebExecutorを使用してダウンロードすることで、GeckoViewのCookie/セッション情報が共有される。
 * 進捗・結果はRoomに書き込み、WorkManagerのprogress/outputDataは使用しない。
 */
internal class DownloadWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val referrerUrl = inputData.getString(KEY_REFERRER_URL).orEmpty()
        // inputDataから通知IDを読み出す（GeckoDownloadManagerと共有）
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, NOTIFICATION_ID)

        val repository = DownloadRepository(context)
        val enqueuedAt = System.currentTimeMillis()

        ensureNotificationChannel(context)
        setForeground(createForegroundInfo(notificationId, 0, true, context.getString(R.string.download_notification_starting), 0L, -1L))

        repository.insertDownload(workerId = id.toString(), url = url, enqueuedAt = enqueuedAt)

        return try {
            val (fileUri, fileName) = downloadFile(url, referrerUrl, notificationId, repository)
            repository.updateCompleted(id.toString(), fileName, fileUri.toString())
            postCompletionNotification(fileName)
            Result.success()
        } catch (e: CancellationException) {
            repository.updateCancelled(id.toString())
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            repository.updateFailed(id.toString())
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
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_COMPLETE_BASE + positiveHash, notification)
    }

    private suspend fun downloadFile(
        urlString: String,
        referrerUrl: String,
        notificationId: Int,
        repository: DownloadRepository,
    ): Pair<android.net.Uri, String> {
        // GeckoRuntime.getDefault() はUIスレッドでのみ呼び出し可能
        val executor = withContext(Dispatchers.Main) {
            val runtime = GeckoRuntime.getDefault(context)
            GeckoWebExecutor(runtime)
        }

        val request = WebRequest.Builder(urlString)
            .apply {
                if (referrerUrl.isNotBlank()) {
                    addHeader("Referer", referrerUrl)
                }
            }
            .build()

        val response = try {
            executor.fetch(request).poll(60_000)
                ?: throw IOException("レスポンスがnullです。")
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("Geckoリクエスト失敗", e)
        }

        val statusCode = response.statusCode
        if (statusCode !in 200..299) {
            response.body?.close()
            throw IOException("HTTP エラー: $statusCode")
        }

        val body = response.body ?: throw IOException("レスポンスボディが空です。")

        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        val mimeType = response.headers["Content-Type"]
            ?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"
        val contentDisposition = response.headers["Content-Disposition"]
        val fileName = URLUtil.guessFileName(urlString, contentDisposition, mimeType)
            .ifBlank { "download-${System.currentTimeMillis()}" }

        setForeground(createForegroundInfo(notificationId, 0, contentLength <= 0, fileName, 0L, contentLength))

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("ダウンロードエントリの作成に失敗しました。")

        try {
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

            val completeValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, completeValues, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Pair(uri, fileName)
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
        const val CHANNEL_ID = "download_progress_channel"
        const val NOTIFICATION_ID = 9001
        /** 完了通知IDのベース。ワークIDのhashCodeを加算して使用する */
        const val NOTIFICATION_ID_COMPLETE_BASE = 10000
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

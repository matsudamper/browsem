package net.matsudamper.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.IOException

/**
 * WorkManagerを使った進捗通知付きダウンロードWorker。
 * GeckoWebExecutorを使用してダウンロードすることで、GeckoViewのCookie/セッション情報が共有される。
 */
internal class DownloadWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val referrerUrl = inputData.getString(KEY_REFERRER_URL).orEmpty()

        ensureNotificationChannel(context)
        setForeground(createForegroundInfo(0, true, context.getString(R.string.download_notification_starting), 0L, -1L))

        return try {
            downloadFile(url, referrerUrl)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun downloadFile(urlString: String, referrerUrl: String) {
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

        setForeground(createForegroundInfo(0, contentLength <= 0, fileName, 0L, contentLength))

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
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val progress = (totalRead * 100 / contentLength).toInt()
                            setForeground(createForegroundInfo(progress, false, fileName, totalRead, contentLength))
                        } else {
                            setForeground(createForegroundInfo(0, true, fileName, totalRead, contentLength))
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
    }

    private fun createForegroundInfo(
        progress: Int,
        indeterminate: Boolean,
        title: String,
        totalRead: Long,
        contentLength: Long,
    ): ForegroundInfo {
        val sizeText = buildSizeText(totalRead, contentLength)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sizeText)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_REFERRER_URL = "referrer_url"
        const val CHANNEL_ID = "download_progress_channel"
        const val NOTIFICATION_ID = 9001

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

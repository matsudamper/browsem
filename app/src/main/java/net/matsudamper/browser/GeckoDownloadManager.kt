package net.matsudamper.browser

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream

internal class GeckoDownloadManager(
    private val context: Context,
    private val runtime: GeckoRuntime,
) {
    /**
     * 画像URLをWorkManagerで非同期ダウンロードするようエンキューする。
     * 進捗は通知で表示される。
     */
    fun enqueueImageDownload(imageUrl: String, referrerUrl: String) {
        DownloadWorker.ensureNotificationChannel(context)
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to imageUrl,
                    DownloadWorker.KEY_REFERRER_URL to referrerUrl,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    // GeckoViewからのWebResponseを直接保存する（ダウンロードリンクのクリック時に使用）
    // レスポンスボディはGeckoViewからのライブストリームのため、WorkManagerではなく通知付きコルーチンで処理する
    suspend fun saveFileFromResponse(response: WebResponse) {
        withContext(Dispatchers.IO) {
            val body = response.body ?: throw IOException("Response body is empty.")
            val contentDisposition = response.headers["Content-Disposition"]
            val mimeType = response.headers["Content-Type"]
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "application/octet-stream"
            val fileName = URLUtil
                .guessFileName(response.uri, contentDisposition, mimeType)
                .ifBlank { "download-${System.currentTimeMillis()}" }
            val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L

            DownloadWorker.ensureNotificationChannel(context)
            val notificationId = response.uri.hashCode()

            body.use { input ->
                saveFileToDownloadsWithNotification(
                    inputStream = input,
                    fileName = fileName,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    notificationId = notificationId,
                )
            }
        }
    }

    private fun saveFileToDownloadsWithNotification(
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        contentLength: Long,
        notificationId: Int,
    ) {
        val canPostNotification = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val notificationManager = NotificationManagerCompat.from(context)

        fun postNotification(progress: Int, indeterminate: Boolean) {
            if (!canPostNotification) return
            val notification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(fileName)
                .setProgress(100, progress, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            notificationManager.notify(notificationId, notification)
        }

        // 進捗不明の場合はインジケータ表示
        postNotification(0, contentLength <= 0)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create download entry.")
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (contentLength > 0) {
                        val progress = (totalRead * 100 / contentLength).toInt()
                        postNotification(progress, false)
                    }
                }
            } ?: throw IOException("Failed to open output stream.")

            val completeValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, completeValues, null, null)

            // 完了通知
            if (canPostNotification) {
                val doneNotification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(fileName)
                    .setContentText("ダウンロード完了")
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(notificationId, doneNotification)
            }
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            // 失敗通知
            if (canPostNotification) {
                val failNotification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(fileName)
                    .setContentText("ダウンロード失敗")
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(notificationId, failNotification)
            }
            throw throwable
        }
    }
}

private fun String.isHttpOrHttps(): Boolean {
    return when (Uri.parse(this).scheme?.lowercase()) {
        "http", "https" -> true
        else -> false
    }
}

private fun <T : Any> GeckoResult<T>.awaitBlocking(): T {
    val value = try {
        poll(60_000)
    } catch (throwable: Throwable) {
        throw IOException("Gecko request failed.", throwable)
    }
    return value ?: throw IOException("Response is null.")
}

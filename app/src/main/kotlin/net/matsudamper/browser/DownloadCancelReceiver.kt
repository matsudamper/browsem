package net.matsudamper.browser

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.download.DownloadRecordStatus
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.download.PendingDownloadBodyStore

/** 通知のキャンセル操作からダウンロードを停止する。 */
internal class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_DOWNLOAD) return

        val currentWorkerId = intent.getStringExtra(EXTRA_CURRENT_WORKER_ID)
            ?.let { runCatching(UUID::fromString).getOrNull() }
            ?: return
        val stableWorkerId = intent.getStringExtra(EXTRA_STABLE_WORKER_ID) ?: currentWorkerId.toString()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, INVALID_NOTIFICATION_ID)
        if (notificationId == INVALID_NOTIFICATION_ID) return

        val pendingResult = goAsync()
        val applicationContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DownloadRepository(applicationContext)
                val workerId = currentWorkerId.toString()

                // Worker の CancellationException ハンドラが CANCELLED と判定できるよう、
                // WorkManager の停止要求より先に DB の状態を更新する。
                repository.updateCancelled(workerId)
                val record = repository.getByCurrentWorkerId(currentWorkerId)
                if (record?.status != DownloadRecordStatus.CANCELLED.name) {
                    return@launch
                }

                // Worker 起動前に保持しているレスポンスがあればここで破棄する。
                PendingDownloadBodyStore.discard(workerId)
                WorkManager.getInstance(applicationContext).cancelWorkById(currentWorkerId)

                // GeckoDownloadManager が Worker 起動前に表示した通知も含め、進捗通知を消す。
                applicationContext.getSystemService(NotificationManager::class.java)
                    ?.cancel(notificationId)

                postCancelledNotification(
                    context = applicationContext,
                    currentWorkerId = currentWorkerId,
                    stableWorkerId = stableWorkerId,
                    fileName = record.fileName,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_CANCEL_DOWNLOAD = "net.matsudamper.browser.ACTION_CANCEL_DOWNLOAD"
        private const val EXTRA_CURRENT_WORKER_ID = "net.matsudamper.browser.EXTRA_CURRENT_WORKER_ID"
        private const val EXTRA_STABLE_WORKER_ID = "net.matsudamper.browser.EXTRA_STABLE_WORKER_ID"
        private const val EXTRA_NOTIFICATION_ID = "net.matsudamper.browser.EXTRA_NOTIFICATION_ID"
        private const val INVALID_NOTIFICATION_ID = -1

        fun createPendingIntent(
            context: Context,
            currentWorkerId: UUID,
            stableWorkerId: String,
            notificationId: Int,
        ): PendingIntent {
            val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_CURRENT_WORKER_ID, currentWorkerId.toString())
                putExtra(EXTRA_STABLE_WORKER_ID, stableWorkerId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun postCancelledNotification(
            context: Context,
            currentWorkerId: UUID,
            stableWorkerId: String,
            fileName: String,
        ) {
            DownloadWorker.ensureNotificationChannel(context)
            val positiveHash = currentWorkerId.hashCode() and 0x7fffffff
            val openDownloadsIntent = Intent(context, MainActivity::class.java).apply {
                action = DownloadWorker.ACTION_OPEN_DOWNLOADS
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(DownloadWorker.EXTRA_WORKER_ID, stableWorkerId)
                putExtra(DownloadWorker.EXTRA_OPEN_DOWNLOADS_REQUEST_ID, "cancelled:$positiveHash")
            }
            val openDownloadsPendingIntent = PendingIntent.getActivity(
                context,
                positiveHash,
                openDownloadsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = fileName.ifBlank { context.getString(R.string.download_notification_cancelled) }
            val notification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.download_notification_cancelled))
                .setContentIntent(openDownloadsPendingIntent)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(DownloadWorker.NOTIFICATION_ID_CANCELLED_BASE + positiveHash, notification)
        }
    }
}

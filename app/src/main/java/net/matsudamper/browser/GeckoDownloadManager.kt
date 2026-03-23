package net.matsudamper.browser

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.download.DownloadRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebResponse

internal class GeckoDownloadManager(
    private val context: Context,
    private val runtime: GeckoRuntime,
    private val downloadRepository: DownloadRepository,
) {
    /**
     * URLをWorkManagerで非同期ダウンロードするようエンキューする。
     * Workerが起動する前にENQUEUEDレコードをRoomに挿入し、UIに即時反映させる。
     * 進捗は通知で表示される。
     */
    fun enqueueDownload(url: String, referrerUrl: String, coroutineScope: CoroutineScope) {
        DownloadWorker.ensureNotificationChannel(context)
        // ダウンロードごとに一意な通知IDを事前に生成し、WorkerとGeckoDownloadManagerで共有する
        val workId = UUID.randomUUID()
        val notificationId = workId.hashCode() and 0x7fffffff
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(workId)
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_REFERRER_URL to referrerUrl,
                    DownloadWorker.KEY_NOTIFICATION_ID to notificationId,
                )
            )
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            .build()
        // WorkerがENQUEUED状態の間もUI上に表示するため、事前にレコードを挿入する
        coroutineScope.launch {
            downloadRepository.insertEnqueued(
                workerId = workRequest.id.toString(),
                url = url,
                enqueuedAt = System.currentTimeMillis(),
            )
        }
        WorkManager.getInstance(context).enqueue(workRequest)
        // Workerが起動する前から即座に通知を表示する
        val notification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.download_notification_starting))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    /**
     * GeckoViewがレンダリングできないレスポンス（ダウンロード対象ファイル等）を受け取った際に、
     * レスポンスボディは破棄し、URLでWorkManagerに再ダウンロードさせる。
     * WorkManagerで実行するため、アプリが終了してもダウンロードが継続される。
     */
    fun enqueueDownloadFromResponse(response: WebResponse, referrerUrl: String, coroutineScope: CoroutineScope) {
        response.body?.close()
        enqueueDownload(url = response.uri, referrerUrl = referrerUrl, coroutineScope = coroutineScope)
    }
}

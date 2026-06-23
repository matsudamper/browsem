package net.matsudamper.browser

import android.app.NotificationManager
import android.content.Context
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.download.PendingDownloadBodyStore
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebResponse

internal class GeckoDownloadManager(
    private val context: Context,
    private val downloadRepository: DownloadRepository,
) {
    /** 指定URLに一致するアクティブなダウンロードを取得する */
    suspend fun findDuplicateDownloads(url: String) = downloadRepository.findActiveByUrl(url)

    /**
     * URLをWorkManagerで非同期ダウンロードするようエンキューする。
     * Workerが起動する前にENQUEUEDレコードをRoomに挿入し、UIに即時反映させる。
     * 進捗は通知で表示される。
     */
    fun enqueueDownload(
        url: String,
        referrerUrl: String,
        coroutineScope: CoroutineScope,
        response: WebResponse? = null,
    ) {
        DownloadWorker.ensureNotificationChannel(context)
        // ダウンロードごとに一意な通知IDを事前に生成し、WorkerとGeckoDownloadManagerで共有する
        val workId = UUID.randomUUID()
        // 元レスポンス（POST結果・ワンタイムURL等）があれば、Workerがボディを直接保存できるよう保持する。
        // プロセス再起動などで取り出せなかった場合はWorker側でURL再取得にフォールバックする。
        if (response != null) {
            PendingDownloadBodyStore.put(workId.toString(), response)
        }
        val notificationId = workId.hashCode() and 0x7fffffff
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(workId)
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_REFERRER_URL to referrerUrl,
                    DownloadWorker.KEY_NOTIFICATION_ID to notificationId,
                    DownloadWorker.KEY_STABLE_WORKER_ID to workId.toString(),
                )
            )
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            .build()
        // WorkerがENQUEUED状態の間もUI上に表示するため、事前にレコードを挿入する
        coroutineScope.launch {
            try {
                downloadRepository.insertEnqueued(
                    workerId = workRequest.id.toString(),
                    url = url,
                    referrerUrl = referrerUrl,
                    enqueuedAt = System.currentTimeMillis(),
                )
                WorkManager.getInstance(context).enqueue(workRequest)
            } catch (e: Throwable) {
                // エンキュー失敗・キャンセル時は保持した元レスポンスのボディを確実に閉じてリークを防ぐ
                PendingDownloadBodyStore.discard(workId.toString())
                throw e
            }
            // Workerが起動する前から即座に通知を表示する
            val notification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.download_notification_starting))
                .setProgress(100, 0, true)
                .setOnlyAlertOnce(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationId, notification)
        }
    }

    /**
     * GeckoViewがレンダリングできないレスポンス（ダウンロード対象ファイル等）を受け取った際に呼ばれる。
     * レスポンスボディ（実データ）を破棄せず保持したままWorkManagerにエンキューし、Workerがそれを直接保存する。
     * これにより、パスワード submit(POST)・ワンタイムURL・セッション依存のダウンロードでも
     * URL再取得による 0 バイト化を避けられる。WorkManagerで実行するため、
     * プロセスが生きている限りバックグラウンドでダウンロードが継続される。
     */
    fun enqueueDownloadFromResponse(response: WebResponse, referrerUrl: String, coroutineScope: CoroutineScope) {
        enqueueDownload(
            url = response.uri,
            referrerUrl = referrerUrl,
            coroutineScope = coroutineScope,
            response = response,
        )
    }

    /**
     * 失敗・一時停止したダウンロードを再開する。
     * レコードは削除せず、既存レコードを新しいワーカーIDへ付け替えてENQUEUEDに戻す。
     * これによりリスト上の位置（enqueuedAt）とUIのアイテム同一性（workerId）が維持され、
     * 再開時に項目がリスト先頭へ飛んだりちらついたりしない。
     * 部分ファイルがある場合はHTTP Rangeリクエストで続きから、
     * 無い場合はURLを再取得して最初からダウンロードする。
     */
    fun resumeDownload(
        workerId: String,
        url: String,
        referrerUrl: String,
        partialFileUri: String?,
        totalRead: Long,
        coroutineScope: CoroutineScope,
    ) {
        DownloadWorker.ensureNotificationChannel(context)
        val newWorkId = UUID.randomUUID()
        val notificationId = newWorkId.hashCode() and 0x7fffffff
        val inputData = buildMap<String, Any> {
            put(DownloadWorker.KEY_URL, url)
            put(DownloadWorker.KEY_REFERRER_URL, referrerUrl)
            put(DownloadWorker.KEY_NOTIFICATION_ID, notificationId)
            put(DownloadWorker.KEY_STABLE_WORKER_ID, workerId)
            // 部分ファイルがある場合のみRange再開モードで起動する
            if (partialFileUri != null) {
                put(DownloadWorker.KEY_PARTIAL_FILE_URI, partialFileUri)
                put(DownloadWorker.KEY_RESUME_FROM_BYTES, totalRead)
            }
        }
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(newWorkId)
            .setInputData(workDataOf(*inputData.toList().toTypedArray()))
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            .build()
        coroutineScope.launch {
            // 既存レコードを新しいワーカーIDへ付け替えてENQUEUEDに戻す（削除・再作成しない）
            downloadRepository.updateResumed(workerId = workerId, newWorkerId = newWorkId.toString())
            WorkManager.getInstance(context).enqueue(workRequest)
            val notification = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.download_notification_resuming))
                .setProgress(100, 0, true)
                .setOnlyAlertOnce(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationId, notification)
        }
    }
}

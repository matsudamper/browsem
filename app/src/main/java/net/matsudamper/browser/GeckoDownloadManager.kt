package net.matsudamper.browser

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebResponse

internal class GeckoDownloadManager(
    private val context: Context,
    private val runtime: GeckoRuntime,
) {
    /**
     * URLをWorkManagerで非同期ダウンロードするようエンキューする。
     * 進捗は通知で表示される。
     */
    fun enqueueDownload(url: String, referrerUrl: String) {
        DownloadWorker.ensureNotificationChannel(context)
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_REFERRER_URL to referrerUrl,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    /**
     * GeckoViewがレンダリングできないレスポンス（ダウンロード対象ファイル等）を受け取った際に、
     * レスポンスボディは破棄し、URLでWorkManagerに再ダウンロードさせる。
     * WorkManagerで実行するため、アプリが終了してもダウンロードが継続される。
     */
    fun enqueueDownloadFromResponse(response: WebResponse, referrerUrl: String) {
        response.body?.close()
        enqueueDownload(url = response.uri, referrerUrl = referrerUrl)
    }
}

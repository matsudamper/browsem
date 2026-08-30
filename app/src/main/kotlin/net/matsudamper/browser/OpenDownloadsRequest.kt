package net.matsudamper.browser

import android.content.Intent

/**
 * ダウンロード通知タップで MainActivity へ配信する要求。
 */
internal data class OpenDownloadsRequest(
    val workerId: String?,
    val requestId: String,
)

internal fun openDownloadsRequestIdFrom(intent: Intent): String {
    return intent.getStringExtra(DownloadWorker.EXTRA_OPEN_DOWNLOADS_REQUEST_ID)
        ?: legacyOpenDownloadsRequestId(intent.getStringExtra(DownloadWorker.EXTRA_WORKER_ID))
}

internal fun legacyOpenDownloadsRequestId(workerId: String?): String = "legacy:${workerId ?: ""}"

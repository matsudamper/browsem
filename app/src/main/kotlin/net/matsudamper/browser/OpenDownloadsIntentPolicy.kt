package net.matsudamper.browser

/**
 * ダウンロード通知 Intent の配信・消費判定。
 */
internal object OpenDownloadsIntentPolicy {
    fun shouldDispatch(
        action: String?,
        intentRequestId: String?,
        consumedRequestId: String?,
    ): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS &&
            (consumedRequestId == null || consumedRequestId != intentRequestId)
    }

    fun shouldClearRestoredIntent(
        action: String?,
        intentRequestId: String?,
        consumedRequestId: String?,
    ): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS &&
            consumedRequestId != null &&
            consumedRequestId == intentRequestId
    }
}

package net.matsudamper.browser

/**
 * ダウンロード通知 Intent の配信・消費判定。
 */
internal object OpenDownloadsIntentPolicy {
    fun shouldDispatch(
        action: String?,
        intentRequestId: String?,
        consumedRequestIds: Set<String>,
    ): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS &&
            intentRequestId !in consumedRequestIds
    }

    fun shouldClearRestoredIntent(
        action: String?,
        intentRequestId: String?,
        consumedRequestIds: Set<String>,
    ): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS &&
            consumedRequestIds.contains(intentRequestId)
    }
}

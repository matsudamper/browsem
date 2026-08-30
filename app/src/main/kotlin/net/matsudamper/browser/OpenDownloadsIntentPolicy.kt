package net.matsudamper.browser

/**
 * ダウンロード通知 Intent の配信・消費判定。
 */
internal object OpenDownloadsIntentPolicy {
    fun shouldDispatch(
        action: String?,
        intentWorkerId: String?,
        consumedWorkerId: String?,
    ): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS &&
            (consumedWorkerId == null || normalizeWorkerId(consumedWorkerId) != normalizeWorkerId(intentWorkerId))
    }

    fun shouldClearRestoredIntent(
        action: String?,
        intentWorkerId: String?,
        consumedWorkerId: String?,
    ): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS &&
            consumedWorkerId != null &&
            normalizeWorkerId(consumedWorkerId) == normalizeWorkerId(intentWorkerId)
    }

    private fun normalizeWorkerId(workerId: String?): String = workerId ?: ""
}

package net.matsudamper.browser

/**
 * ダウンロード通知 Intent の配信・消費判定。
 */
internal object OpenDownloadsIntentPolicy {
    fun shouldDispatch(action: String?, consumed: Boolean): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS && !consumed
    }

    fun shouldClearRestoredIntent(action: String?, consumed: Boolean): Boolean {
        return action == DownloadWorker.ACTION_OPEN_DOWNLOADS && consumed
    }

    fun isConsumed(action: String?): Boolean {
        return action != DownloadWorker.ACTION_OPEN_DOWNLOADS
    }
}

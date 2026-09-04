package net.matsudamper.browser.download

/**
 * レスポンス由来のダウンロード確定処理の順序を保証する。
 *
 * 1. 通知権限待機
 * 2. WorkManager エンキュー完了
 * 3. タブ閉鎖コールバック
 *
 * エンキュー前にタブを閉じると rememberCoroutineScope がキャンセルされ、
 * ダウンロードが開始されない不具合を防ぐ。
 */
internal suspend inline fun proceedDownloadFromResponse(
    awaitPermission: suspend () -> Unit,
    enqueue: suspend () -> Unit,
    noinline onEnqueued: (() -> Unit)? = null,
    noinline onEnqueueFailed: (() -> Unit)? = null,
) {
    var enqueued = false
    try {
        awaitPermission()
        enqueue()
        enqueued = true
        onEnqueued?.invoke()
    } finally {
        if (!enqueued) {
            onEnqueueFailed?.invoke()
        }
    }
}

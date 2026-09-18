package net.matsudamper.browser.download

/**
 * Worker 起動前の通知を出してからエンキューする。
 *
 * エンキューより後に通知を出すと、小さいファイルなどで Worker が先に完了した場合、
 * フォアグラウンド通知の削除より後から同じ ID の通知が張り付き、誰も消さない通知が残る。
 * エンキューに失敗した場合は Worker が起動せず通知も更新されないため、ここで消す。
 */
internal suspend fun enqueueDownloadWithStartingNotification(
    showStartingNotification: () -> Unit,
    enqueue: suspend () -> Unit,
    dismissStartingNotification: () -> Unit,
) {
    showStartingNotification()
    try {
        enqueue()
    } catch (e: Throwable) {
        dismissStartingNotification()
        throw e
    }
}

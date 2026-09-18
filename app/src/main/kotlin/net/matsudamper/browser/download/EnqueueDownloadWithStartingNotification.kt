package net.matsudamper.browser.download

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * レコードを作り、Worker 起動前の通知を出してからエンキューする。
 *
 * 通知のキャンセルはレコードを CANCELLED にして Worker を止めるため、レコードは通知より先に作る。
 * 逆順だと、レコードが無い間に押されたキャンセルが取りこぼされ、その後エンキューされた
 * ダウンロードがそのまま走り出す。
 *
 * 通知をエンキューより後に出すと、小さいファイルなどで Worker が先に完了した場合、
 * フォアグラウンド通知の削除より後から同じ ID の通知が張り付き、誰も消さない通知が残る。
 *
 * 失敗時の後始末はキャンセル済みのコルーチンでも実行する必要があるため NonCancellable で囲む。
 * 通知を出す前やレコードを作る前に失敗した場合も呼ばれるので、後始末は何度呼ばれても
 * 問題ない処理にする。
 */
internal suspend fun enqueueDownloadWithStartingNotification(
    prepareRecord: suspend () -> Unit,
    showStartingNotification: () -> Unit,
    enqueue: suspend () -> Unit,
    cleanUpFailedEnqueue: suspend () -> Unit,
) {
    try {
        prepareRecord()
        showStartingNotification()
        enqueue()
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            cleanUpFailedEnqueue()
        }
        throw e
    }
}

package net.matsudamper.browser.download

/**
 * 通知のキャンセル操作で実行する手順。
 *
 * レコードを CANCELLED へ遷移できなくても、Worker の停止と進捗通知の消去は必ず行う。
 * 遷移できないのは完了・失敗が確定している場合だけでなく、再開でレコードの currentWorkerId が
 * 別 Worker へ付け替わり、この Worker では対象行を引けなくなった場合もある。
 * 後者を「もう止まっている」とみなすと、走り続ける Worker と進捗通知が残り続ける。
 *
 * 部分ファイルの削除とキャンセル通知は、遷移できたレコードに対してのみ行う。
 */
internal suspend fun cancelDownloadFromNotification(
    markCancelled: suspend () -> Boolean,
    deletePartialFile: suspend () -> Unit,
    stopWorker: () -> Unit,
    dismissProgressNotification: () -> Unit,
    postCancelledNotification: suspend () -> Unit,
) {
    val cancelled = markCancelled()
    if (cancelled) {
        deletePartialFile()
    }
    stopWorker()
    dismissProgressNotification()
    if (cancelled) {
        postCancelledNotification()
    }
}

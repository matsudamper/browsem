package net.matsudamper.browser.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知のキャンセル操作の手順を検証する。
 *
 * レコードを CANCELLED へ遷移できない場合でも Worker の停止と進捗通知の消去を行わないと、
 * 再開でレコードが付け替わった古い Worker の通知が消せなくなる。
 */
class CancelDownloadFromNotificationTest {

    @Test
    fun `遷移できた場合は部分ファイル削除からキャンセル通知まで実行する`() = runTest {
        val events = mutableListOf<String>()

        cancelDownloadFromNotification(
            markCancelled = { true },
            deletePartialFile = { events.add("delete_partial_file") },
            stopWorker = { events.add("stop_worker") },
            dismissProgressNotification = { events.add("dismiss_progress_notification") },
            postCancelledNotification = { events.add("post_cancelled_notification") },
        )

        assertEquals(
            listOf(
                "delete_partial_file",
                "stop_worker",
                "dismiss_progress_notification",
                "post_cancelled_notification",
            ),
            events,
        )
    }

    @Test
    fun `遷移できなくても Worker 停止と進捗通知の消去は実行する`() = runTest {
        val events = mutableListOf<String>()

        cancelDownloadFromNotification(
            markCancelled = { false },
            deletePartialFile = { events.add("delete_partial_file") },
            stopWorker = { events.add("stop_worker") },
            dismissProgressNotification = { events.add("dismiss_progress_notification") },
            postCancelledNotification = { events.add("post_cancelled_notification") },
        )

        assertEquals(listOf("stop_worker", "dismiss_progress_notification"), events)
    }
}

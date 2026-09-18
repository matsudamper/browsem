package net.matsudamper.browser.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worker 起動前の通知とエンキューの順序を検証する。
 *
 * エンキューより後に通知を出すと、Worker が先に完了した場合に消えない通知が残る。
 */
class EnqueueDownloadWithStartingNotificationTest {

    @Test
    fun `通知を出してからエンキューする`() = runTest {
        val events = mutableListOf<String>()

        enqueueDownloadWithStartingNotification(
            showStartingNotification = { events.add("show") },
            enqueue = { events.add("enqueue") },
            dismissStartingNotification = { events.add("dismiss") },
        )

        assertEquals(listOf("show", "enqueue"), events)
    }

    @Test
    fun `エンキューに失敗したら通知を消す`() = runTest {
        val events = mutableListOf<String>()

        val thrown = runCatching {
            enqueueDownloadWithStartingNotification(
                showStartingNotification = { events.add("show") },
                enqueue = { error("エンキュー失敗") },
                dismissStartingNotification = { events.add("dismiss") },
            )
        }.exceptionOrNull()

        assertTrue("エンキューの失敗は呼び出し元へ伝える", thrown is IllegalStateException)
        assertEquals(listOf("show", "dismiss"), events)
    }
}

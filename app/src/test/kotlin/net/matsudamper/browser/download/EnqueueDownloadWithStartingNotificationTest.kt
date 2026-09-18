package net.matsudamper.browser.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * レコード作成・開始通知・エンキューの順序を検証する。
 *
 * レコードより先に通知を出すと、レコードが無い間に押されたキャンセルが取りこぼされる。
 * エンキューより後に通知を出すと、Worker が先に完了した場合に消えない通知が残る。
 */
class EnqueueDownloadWithStartingNotificationTest {

    @Test
    fun `レコードを作り通知を出してからエンキューする`() = runTest {
        val events = mutableListOf<String>()

        enqueueDownloadWithStartingNotification(
            prepareRecord = { events.add("prepare_record") },
            showStartingNotification = { events.add("show") },
            enqueue = { events.add("enqueue") },
            cleanUpFailedEnqueue = { events.add("clean_up") },
        )

        assertEquals(listOf("prepare_record", "show", "enqueue"), events)
    }

    @Test
    fun `エンキューに失敗したら後始末する`() = runTest {
        val events = mutableListOf<String>()

        val thrown = runCatching {
            enqueueDownloadWithStartingNotification(
                prepareRecord = { events.add("prepare_record") },
                showStartingNotification = { events.add("show") },
                enqueue = { error("エンキュー失敗") },
                cleanUpFailedEnqueue = { events.add("clean_up") },
            )
        }.exceptionOrNull()

        assertTrue("エンキューの失敗は呼び出し元へ伝える", thrown is IllegalStateException)
        assertEquals(listOf("prepare_record", "show", "clean_up"), events)
    }

    @Test
    fun `レコードの作成に失敗したら通知を出さずに後始末する`() = runTest {
        val events = mutableListOf<String>()

        val thrown = runCatching {
            enqueueDownloadWithStartingNotification(
                prepareRecord = { error("レコード作成失敗") },
                showStartingNotification = { events.add("show") },
                enqueue = { events.add("enqueue") },
                cleanUpFailedEnqueue = { events.add("clean_up") },
            )
        }.exceptionOrNull()

        assertTrue("レコード作成の失敗は呼び出し元へ伝える", thrown is IllegalStateException)
        assertEquals(listOf("clean_up"), events)
    }
}

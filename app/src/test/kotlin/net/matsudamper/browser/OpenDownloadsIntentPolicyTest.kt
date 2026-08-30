package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDownloadsIntentPolicyTest {

    @Test
    fun `未消費の通知 Intent は配信対象`() {
        assertTrue(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "progress:9001",
                consumedRequestIds = emptySet(),
            ),
        )
    }

    @Test
    fun `同じ request ID を消費済みなら配信しない`() {
        assertFalse(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "progress:9001",
                consumedRequestIds = setOf("progress:9001"),
            ),
        )
    }

    @Test
    fun `同じ worker ID でも別 request ID の通知は配信対象`() {
        assertTrue(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "complete:12345",
                consumedRequestIds = setOf("progress:9001"),
            ),
        )
    }

    @Test
    fun `プロセス復元後に同じ request ID の消費済み Intent が残っていればクリア対象`() {
        assertTrue(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "progress:9001",
                consumedRequestIds = setOf("progress:9001"),
            ),
        )
    }

    @Test
    fun `プロセス復元後に別 request ID の Intent はクリア対象にしない`() {
        assertFalse(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "complete:12345",
                consumedRequestIds = setOf("progress:9001"),
            ),
        )
    }

    @Test
    fun `複数消費済みでも AMS 復元 Intent が集合に含まれればクリアし再配信しない`() {
        val consumed = setOf("request-a", "request-b")
        assertTrue(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "request-a",
                consumedRequestIds = consumed,
            ),
        )
        assertFalse(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "request-a",
                consumedRequestIds = consumed,
            ),
        )
        assertTrue(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "request-c",
                consumedRequestIds = consumed,
            ),
        )
    }

    @Test
    fun `再配信時は消費済み集合から request ID を除去して配信可能にする`() {
        val consumed = mutableSetOf("progress:9001")
        consumed.remove("progress:9001")
        assertTrue(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentRequestId = "progress:9001",
                consumedRequestIds = consumed,
            ),
        )
    }
}

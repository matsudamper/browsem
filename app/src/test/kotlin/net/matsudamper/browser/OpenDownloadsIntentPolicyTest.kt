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
                intentWorkerId = "worker-1",
                consumedWorkerId = null,
            ),
        )
    }

    @Test
    fun `同じ worker ID を消費済みなら配信しない`() {
        assertFalse(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentWorkerId = "worker-1",
                consumedWorkerId = "worker-1",
            ),
        )
    }

    @Test
    fun `別の worker ID を消費済みでも新しい通知は配信対象`() {
        assertTrue(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentWorkerId = "worker-2",
                consumedWorkerId = "worker-1",
            ),
        )
    }

    @Test
    fun `プロセス復元後に同じ worker ID の消費済み Intent が残っていればクリア対象`() {
        assertTrue(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentWorkerId = "worker-1",
                consumedWorkerId = "worker-1",
            ),
        )
    }

    @Test
    fun `プロセス復元後に別 worker ID の Intent はクリア対象にしない`() {
        assertFalse(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentWorkerId = "worker-2",
                consumedWorkerId = "worker-1",
            ),
        )
    }

    @Test
    fun `未消費なら復元 Intent はクリア対象にしない`() {
        assertFalse(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                intentWorkerId = "worker-1",
                consumedWorkerId = null,
            ),
        )
    }
}

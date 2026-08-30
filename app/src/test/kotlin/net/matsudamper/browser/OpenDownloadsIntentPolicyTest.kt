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
                consumed = false,
            ),
        )
    }

    @Test
    fun `消費済みフラグがあれば配信しない`() {
        assertFalse(
            OpenDownloadsIntentPolicy.shouldDispatch(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                consumed = true,
            ),
        )
    }

    @Test
    fun `プロセス復元後に消費済み Intent が残っていればクリア対象`() {
        assertTrue(
            OpenDownloadsIntentPolicy.shouldClearRestoredIntent(
                DownloadWorker.ACTION_OPEN_DOWNLOADS,
                consumed = true,
            ),
        )
    }
}

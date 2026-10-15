package net.matsudamper.browser.data.download

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadUnstartedFailureRecoveryTest {
    private val repository = DownloadRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `起動前に失敗した履歴を失敗状態へ同期できる`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertEnqueued(workerId, "https://example.com/file", "", 0L)

        assertTrue(repository.markFailedIfActive(workerId, FAILURE_REASON))

        val record = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.FAILED.name, record?.status)
        assertEquals(FAILURE_REASON, record?.failureReason)
    }

    @Test
    fun `失敗確定後も明示的なキャンセルは上書きしない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertEnqueued(workerId, "https://example.com/file", "", 0L)
        repository.updateCancelled(workerId)

        assertFalse(repository.markFailedIfActive(workerId, FAILURE_REASON))
        assertFalse(repository.cancelInactiveIfActive(workerId))

        val record = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.CANCELLED.name, record?.status)
    }

    @Test
    fun `同期中に一時停止された履歴は上書きしない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId, "https://example.com/file", "", 0L)
        repository.updatePaused(workerId)

        assertFalse(repository.markFailedIfActive(workerId, FAILURE_REASON))
        assertFalse(repository.cancelInactiveIfActive(workerId))

        val record = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.PAUSED.name, record?.status)
    }

    @Test
    fun `終了済みワークの実行中履歴はキャンセルへ同期する`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertEnqueued(workerId, "https://example.com/file", "", 0L)

        assertTrue(repository.cancelInactiveIfActive(workerId))

        val record = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.CANCELLED.name, record?.status)
    }

    private companion object {
        const val FAILURE_REASON = "ワーカーの起動に失敗"
    }
}

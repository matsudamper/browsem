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
    fun `起動前に失敗してキャンセル扱いになった履歴を復旧する`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertEnqueued(workerId, "https://example.com/file", "", 0L)
        repository.updateCancelled(workerId)

        assertTrue(repository.restoreUnstartedFailure(workerId, FAILURE_REASON))

        val record = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.FAILED.name, record?.status)
        assertEquals(FAILURE_REASON, record?.failureReason)
    }

    @Test
    fun `進捗があったキャンセル済み履歴は変更しない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId, "https://example.com/file", "", 0L)
        repository.updateProgress(workerId, "file", 25, 25L, 100L)
        repository.updateCancelled(workerId)

        assertFalse(repository.restoreUnstartedFailure(workerId, FAILURE_REASON))

        val record = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.CANCELLED.name, record?.status)
    }

    private companion object {
        const val FAILURE_REASON = "ワーカーの起動に失敗"
    }
}

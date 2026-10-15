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

/**
 * Worker が自力で停止するかどうかの判定を検証する。
 */
@RunWith(RobolectricTestRunner::class)
class DownloadStopRequestTest {

    private val repository = DownloadRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `実行中のワーカーは停止要求ではない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)

        assertFalse(repository.isStopRequested(workerId))
    }

    @Test
    fun `キャンセル済みのワーカーは停止要求`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updateCancelled(workerId)

        assertTrue(repository.isStopRequested(workerId))
    }

    @Test
    fun `再開でレコードを付け替えられた古いワーカーは停止要求`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        val newWorkerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updatePaused(workerId)
        repository.updateResumed(workerId = workerId, newWorkerId = newWorkerId)

        assertTrue("付け替え前のワーカーは停止する", repository.isStopRequested(workerId))
        assertFalse("付け替え後のワーカーは走り続ける", repository.isStopRequested(newWorkerId))
    }

    @Test
    fun `完了済みのワーカーは確定済み`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updateCompleted(currentWorkerId = workerId, fileName = "file.bin", fileUri = "content://media/external/downloads/1")

        assertTrue(repository.isFinished(workerId))
    }

    @Test
    fun `実行中のワーカーは確定済みではない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)

        assertFalse(repository.isFinished(workerId))
    }

    @Test
    fun `レコードが無いワーカーは確定済みではない`() = runBlocking {
        assertFalse(repository.isFinished(UUID.randomUUID().toString()))
    }

    @Test
    fun `残った進捗を安定IDでキャンセルできる`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertEnqueued(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)

        val cancelledRecord = repository.cancelByWorkerId(workerId)

        assertEquals(UUID.fromString(workerId), cancelledRecord?.currentWorkerId)
        assertTrue(repository.isStopRequested(workerId))
        assertEquals(DownloadRecordStatus.CANCELLED.name, repository.getByCurrentWorkerId(UUID.fromString(workerId))?.status)
    }

    @Test
    fun `再開後も元の安定IDで現在のワーカーをキャンセルできる`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        val newWorkerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updatePaused(workerId)
        repository.updateResumed(workerId = workerId, newWorkerId = newWorkerId)

        val cancelledRecord = repository.cancelByWorkerId(workerId)

        assertEquals(UUID.fromString(newWorkerId), cancelledRecord?.currentWorkerId)
        assertTrue(repository.isStopRequested(newWorkerId))
        assertEquals(DownloadRecordStatus.CANCELLED.name, repository.getByCurrentWorkerId(UUID.fromString(newWorkerId))?.status)
    }

    @Test
    fun `キャンセル済みレコードは再開や再開失敗の復元で復活しない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        val newWorkerId = UUID.randomUUID().toString()
        val anotherWorkerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updatePaused(workerId)
        val revertPoint = repository.updateResumed(workerId = workerId, newWorkerId = newWorkerId)
        repository.cancelByWorkerId(workerId)
        requireNotNull(revertPoint)

        repository.revertResumed(workerId = workerId, newWorkerId = newWorkerId, revertPoint = revertPoint)
        assertEquals(null, repository.updateResumed(workerId = workerId, newWorkerId = anotherWorkerId))

        assertEquals(DownloadRecordStatus.CANCELLED.name, repository.getByCurrentWorkerId(UUID.fromString(newWorkerId))?.status)
        assertTrue(repository.isStopRequested(newWorkerId))
    }

    @Test
    fun `完了済みレコードは安定IDでキャンセルしても変更されない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updateCompleted(workerId, "file.bin", "content://media/external/downloads/1")

        assertEquals(null, repository.cancelByWorkerId(workerId))
        assertEquals(DownloadRecordStatus.SUCCEEDED.name, repository.getByCurrentWorkerId(UUID.fromString(workerId))?.status)
    }

    private companion object {
        const val URL = "https://example.com/file.bin"
    }
}

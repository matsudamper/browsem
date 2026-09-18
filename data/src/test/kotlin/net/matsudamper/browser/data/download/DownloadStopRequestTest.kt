package net.matsudamper.browser.data.download

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
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

    private companion object {
        const val URL = "https://example.com/file.bin"
    }
}

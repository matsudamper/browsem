package net.matsudamper.browser.data.download

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 再開のエンキューに失敗したときに、付け替え前の状態へ戻せることを検証する。
 */
@RunWith(RobolectricTestRunner::class)
class DownloadResumeRevertTest {

    private val repository = DownloadRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `付け替え前の状態へ戻し部分ファイルを保持する`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        val newWorkerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updatePaused(workerId)
        repository.updatePausedPartial(
            currentWorkerId = workerId,
            partialFileUri = PARTIAL_FILE_URI,
            fileName = "file.bin",
            totalRead = 100L,
            contentLength = 200L,
        )

        val revertPoint = repository.updateResumed(workerId = workerId, newWorkerId = newWorkerId)
        assertEquals(DownloadRecordStatus.PAUSED, revertPoint?.status)
        assertEquals(workerId, revertPoint?.currentWorkerId)

        requireNotNull(revertPoint).let {
            repository.revertResumed(workerId = workerId, newWorkerId = newWorkerId, revertPoint = it)
        }

        val reverted = repository.getByCurrentWorkerId(UUID.fromString(workerId))
        assertEquals(DownloadRecordStatus.PAUSED.name, reverted?.status)
        assertEquals("再開のやり直しに使う部分ファイルを残す", PARTIAL_FILE_URI, reverted?.partialFileUri)
        assertNull("付け替えたワーカーIDのレコードは残さない", repository.getByCurrentWorkerId(UUID.fromString(newWorkerId)))
    }

    @Test
    fun `付け替え後に状態が変わっていれば戻さない`() = runBlocking {
        val workerId = UUID.randomUUID().toString()
        val newWorkerId = UUID.randomUUID().toString()
        val furtherWorkerId = UUID.randomUUID().toString()
        repository.insertDownload(workerId = workerId, url = URL, referrerUrl = "", enqueuedAt = 0L)
        repository.updatePaused(workerId)
        val revertPoint = requireNotNull(repository.updateResumed(workerId = workerId, newWorkerId = newWorkerId))
        repository.updateResumed(workerId = workerId, newWorkerId = furtherWorkerId)

        repository.revertResumed(workerId = workerId, newWorkerId = newWorkerId, revertPoint = revertPoint)

        val current = repository.getByCurrentWorkerId(UUID.fromString(furtherWorkerId))
        assertEquals(DownloadRecordStatus.ENQUEUED.name, current?.status)
    }

    private companion object {
        const val URL = "https://example.com/file.bin"
        const val PARTIAL_FILE_URI = "content://media/external/downloads/1"
    }
}

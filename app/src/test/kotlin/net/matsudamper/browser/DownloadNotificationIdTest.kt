package net.matsudamper.browser

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationIdTest {

    @Test
    fun `同じワーカーでも通知の種別ごとに別の ID になる`() {
        val workerId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val ids = listOf(
            DownloadNotificationId.progress(workerId),
            DownloadNotificationId.complete(workerId),
            DownloadNotificationId.failure(workerId),
            DownloadNotificationId.cancelled(workerId),
        )

        assertEquals("種別ごとに別の値でないと PendingIntent が共有される", ids.size, ids.toSet().size)
    }

    @Test
    fun `ワーカーが違えば進捗通知の ID も違う`() {
        assertNotEquals(
            DownloadNotificationId.progress(UUID.fromString("00000000-0000-4000-8000-000000000001")),
            DownloadNotificationId.progress(UUID.fromString("00000000-0000-4000-8000-000000000002")),
        )
    }

    @Test
    fun `進捗通知の ID は非負`() {
        repeat(100) {
            assertTrue(DownloadNotificationId.progress(UUID.randomUUID()) >= 0)
        }
    }
}

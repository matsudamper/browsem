package net.matsudamper.browser

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateProgressTest {
    @Test
    fun `受信がなければ短い期限で現在の世代を返す`() = runTest {
        val progress = SessionStateProgress()
        val capture = async { progress.flushAndAwait(300, 5_000) {} }
        delay(400)
        assertTrue(capture.isCompleted)
        val result = capture.await()
        assertEquals(0L, result.received)
        assertTrue(result.receiveTimedOut)
        assertFalse(result.timedOut)
    }

    @Test
    fun `文字列の変化に依存せず受信世代の反映を待つ`() = runTest {
        val progress = SessionStateProgress()
        val capture = async {
            progress.flushAndAwait(300, 5_000) { progress.received() }
        }
        delay(400)
        assertFalse(capture.isCompleted)
        progress.applied(1)
        val result = capture.await()
        assertEquals(1L, result.applied)
        assertFalse(result.timedOut)
    }

    @Test
    fun `文字列化中に届いた新しい世代の反映まで待つ`() = runTest {
        val progress = SessionStateProgress()
        val capture = async {
            progress.flushAndAwait(300, 5_000) { progress.received() }
        }
        delay(400)
        val latest = progress.received()
        progress.applied(1)
        delay(1)
        assertFalse(capture.isCompleted)
        progress.applied(latest)
        val result = capture.await()
        assertEquals(latest, result.received)
        assertEquals(latest, result.applied)
    }

    @Test
    fun `flush 前から処理中なら新しい受信がなくても反映を待つ`() = runTest {
        val progress = SessionStateProgress()
        val generation = progress.received()
        val capture = async { progress.flushAndAwait(300, 5_000) {} }
        delay(400)
        assertFalse(capture.isCompleted)
        progress.applied(generation)
        assertEquals(generation, capture.await().applied)
    }

    @Test
    fun `flush 後に非同期で届いた状態と集約された世代を待つ`() = runTest {
        val progress = SessionStateProgress()
        val capture = async { progress.flushAndAwait(300, 5_000) {} }
        delay(100)
        progress.received()
        val latest = progress.received()
        delay(400)
        assertFalse(capture.isCompleted)
        progress.applied(latest)
        val result = capture.await()
        assertEquals(0L, result.before)
        assertEquals(latest, result.received)
        assertEquals(latest, result.applied)
        assertFalse(result.timedOut)
    }

    @Test
    fun `反映が終わらなければ上限で待機を終了する`() = runTest {
        val progress = SessionStateProgress()
        val result = progress.flushAndAwait(300, 5_000) { progress.received() }
        assertTrue(result.timedOut)
        assertEquals(1L, result.received)
        assertEquals(0L, result.applied)
    }

    @Test
    fun `破棄で反映待ちを終了する`() = runTest {
        val progress = SessionStateProgress()
        val capture = async {
            progress.flushAndAwait(300, 5_000) { progress.received() }
        }
        delay(100)
        progress.close()
        val result = capture.await()
        assertTrue(result.closed)
        assertFalse(result.timedOut)
    }
}

package net.matsudamper.browser.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadEngineTest {

    private val engine = DownloadEngine()

    @Test
    fun copyToCopiesAllBytes() = runTest {
        // 複数バッファをまたぐサイズでも全バイトが欠落なくコピーされる
        val data = ByteArray(DownloadEngine.BUFFER_SIZE * 2 + 123) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()

        val total = engine.copyTo(ByteArrayInputStream(data), out, expectedTotalLength = data.size.toLong())

        assertEquals(data.size.toLong(), total)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun copyToWorksWithoutContentLength() = runTest {
        // Content-Length 不明（-1）でもストリーム終端まで読み切る
        val data = ByteArray(1000) { 7 }
        val out = ByteArrayOutputStream()

        val total = engine.copyTo(ByteArrayInputStream(data), out, expectedTotalLength = -1L)

        assertEquals(1000L, total)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun copyToThrowsWhenTruncated() = runTest {
        // Content-Length が判明しているのに本文が途中で終端した場合は切断として失敗する。
        // これにより「Content-Length ありで 0 バイト/部分バイト」を成功扱いにしない。
        val data = ByteArray(50)
        val out = ByteArrayOutputStream()

        var thrown: DownloadTruncatedException? = null
        try {
            engine.copyTo(ByteArrayInputStream(data), out, expectedTotalLength = 100L)
        } catch (e: DownloadTruncatedException) {
            thrown = e
        }

        assertNotNull("期待バイト数に満たない場合は例外を投げるべき", thrown)
        assertEquals(50L, thrown?.totalRead)
        assertEquals(100L, thrown?.expectedLength)
    }

    @Test
    fun copyToThrowsWhenContentLengthSetButBodyEmpty() = runTest {
        // Content-Length > 0 だが本文が完全に空（0バイト）のケース = 0バイト「成功」の防止
        val out = ByteArrayOutputStream()

        var thrown: DownloadTruncatedException? = null
        try {
            engine.copyTo(ByteArrayInputStream(ByteArray(0)), out, expectedTotalLength = 2048L)
        } catch (e: DownloadTruncatedException) {
            thrown = e
        }

        assertNotNull("Content-Length あり・空ボディは切断として失敗すべき", thrown)
        assertEquals(0L, thrown?.totalRead)
    }

    @Test
    fun copyToReportsCumulativeProgress() = runTest {
        val data = ByteArray(DownloadEngine.BUFFER_SIZE * 3) { 1 }
        val out = ByteArrayOutputStream()
        val progresses = mutableListOf<Long>()

        engine.copyTo(ByteArrayInputStream(data), out, expectedTotalLength = data.size.toLong()) { totalRead ->
            progresses.add(totalRead)
        }

        // 進捗は単調増加し、最後は総バイト数に一致する
        assertEquals(data.size.toLong(), progresses.last())
        assertTrue("進捗は単調増加すべき", progresses.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun copyToUsesStartBytesAsProgressBase() = runTest {
        // 再開時: startBytes を進捗の起点とし、書き込みは今回読み取った分のみ
        val data = ByteArray(200) { 9 }
        val out = ByteArrayOutputStream()
        val progresses = mutableListOf<Long>()

        val total = engine.copyTo(
            body = ByteArrayInputStream(data),
            sink = out,
            expectedTotalLength = 1200L,
            startBytes = 1000L,
        ) { totalRead ->
            progresses.add(totalRead)
        }

        assertEquals(1200L, total)
        assertEquals(1200L, progresses.last())
        assertTrue("進捗は startBytes 以上であるべき", progresses.all { it >= 1000L })
        assertArrayEquals(data, out.toByteArray())
    }
}

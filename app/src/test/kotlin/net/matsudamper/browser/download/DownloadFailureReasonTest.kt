package net.matsudamper.browser.download

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureReasonTest {

    @Test
    fun truncatedExceptionReportsReadAndExpectedBytes() {
        val reason = DownloadFailureReason.from(
            DownloadTruncatedException(totalRead = 1024, expectedLength = 5 * 1024 * 1024),
        )
        assertTrue(reason, reason.contains("1.0 KB"))
        assertTrue(reason, reason.contains("5.0 MB"))
    }

    @Test
    fun networkExceptionsAreTranslated() {
        assertEquals(
            "サーバーに接続できませんでした",
            DownloadFailureReason.from(UnknownHostException("example.com")),
        )
        assertEquals(
            "通信がタイムアウトしました",
            DownloadFailureReason.from(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun ioExceptionMessageIsUsedAsIs() {
        assertEquals("HTTP エラー: 404", DownloadFailureReason.from(IOException("HTTP エラー: 404")))
    }

    @Test
    fun messagelessExceptionFallsBackToCauseThenClassName() {
        // メッセージが無い場合は原因のメッセージを使う
        assertEquals(
            "書き込みに失敗しました",
            DownloadFailureReason.from(IllegalStateException(null, IOException("書き込みに失敗しました"))),
        )
        // 原因も無い場合でも「不明」で終わらせず、例外クラス名を残す
        assertEquals(
            "NullPointerException",
            DownloadFailureReason.from(NullPointerException()),
        )
    }
}

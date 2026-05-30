package net.matsudamper.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMetadataTest {

    @Test
    fun parseMimeTypeStripsParameters() {
        // Content-Type のパラメータ部（charset 等）を除去する
        assertEquals("application/pdf", DownloadMetadata.parseMimeType("application/pdf; charset=utf-8"))
        assertEquals("text/html", DownloadMetadata.parseMimeType("text/html"))
    }

    @Test
    fun parseMimeTypeFallsBackToOctetStream() {
        // null・空・空白は application/octet-stream にフォールバックする
        assertEquals("application/octet-stream", DownloadMetadata.parseMimeType(null))
        assertEquals("application/octet-stream", DownloadMetadata.parseMimeType(""))
        assertEquals("application/octet-stream", DownloadMetadata.parseMimeType("   "))
    }

    @Test
    fun parseContentLengthParsesNumber() {
        assertEquals(1234L, DownloadMetadata.parseContentLength("1234"))
        assertEquals(0L, DownloadMetadata.parseContentLength("0"))
        // 解析できない値は -1（不明）
        assertEquals(-1L, DownloadMetadata.parseContentLength(null))
        assertEquals(-1L, DownloadMetadata.parseContentLength("abc"))
    }

    @Test
    fun parseTotalFromContentRangeExtractsTotal() {
        // Content-Range: bytes START-END/TOTAL の TOTAL を取り出す
        assertEquals(5000L, DownloadMetadata.parseTotalFromContentRange("bytes 1000-4999/5000"))
        // TOTAL が "*" の場合は不明
        assertNull(DownloadMetadata.parseTotalFromContentRange("bytes 1000-4999/*"))
        assertNull(DownloadMetadata.parseTotalFromContentRange(null))
    }
}

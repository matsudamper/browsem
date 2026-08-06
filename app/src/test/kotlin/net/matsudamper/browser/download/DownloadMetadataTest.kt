package net.matsudamper.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun isSuccessStatusAcceptsSuccessRange() {
        assertTrue(DownloadMetadata.isSuccessStatus(200))
        assertTrue(DownloadMetadata.isSuccessStatus(206))
        assertTrue(DownloadMetadata.isSuccessStatus(299))
    }

    /**
     * blob: など非HTTPのレスポンスは GeckoView がステータスコードを設定せず 0 になる。
     * これをエラー扱いすると Material Symbols の SVG ダウンロード（blob:null/...）が
     * 「HTTP エラー: 0」で必ず失敗するため、成功として扱う
     */
    @Test
    fun isSuccessStatusAcceptsNonHttpResponse() {
        assertTrue(DownloadMetadata.isSuccessStatus(DownloadMetadata.NO_HTTP_STATUS))
        assertEquals(0, DownloadMetadata.NO_HTTP_STATUS)
    }

    @Test
    fun isSuccessStatusRejectsErrorStatus() {
        assertFalse(DownloadMetadata.isSuccessStatus(301))
        assertFalse(DownloadMetadata.isSuccessStatus(403))
        assertFalse(DownloadMetadata.isSuccessStatus(404))
        assertFalse(DownloadMetadata.isSuccessStatus(500))
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

package net.matsudamper.browser.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUrlTest {

    @Test
    fun httpUrlIsRefetchable() {
        assertTrue(DownloadUrl.isRefetchable("https://example.com/a.zip"))
        assertTrue(DownloadUrl.isRefetchable("http://example.com/a.zip"))
        assertTrue(DownloadUrl.isRefetchable("data:image/svg+xml,%3Csvg%2F%3E"))
    }

    /**
     * Google Fonts (Material Symbols) の SVG ダウンロードは
     * 不透明オリジンで生成された blob:null/<uuid> 形式のURLになる
     */
    @Test
    fun blobUrlIsNotRefetchable() {
        assertFalse(DownloadUrl.isRefetchable("blob:null/1f0e3dad-99908345-f4c1-a8e3"))
        assertFalse(DownloadUrl.isRefetchable("blob:https://fonts.google.com/1f0e3dad-9990"))
        // スキームは大文字小文字を区別しない
        assertFalse(DownloadUrl.isRefetchable("BLOB:null/1f0e3dad-9990"))
    }

    @Test
    fun malformedUrlIsTreatedAsRefetchable() {
        // スキームを判別できない文字列は blob ではないため再取得を試みる
        assertTrue(DownloadUrl.isRefetchable(""))
        assertTrue(DownloadUrl.isRefetchable("example.com/a.zip"))
        assertTrue(DownloadUrl.isRefetchable(":no-scheme"))
    }
}

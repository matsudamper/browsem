package net.matsudamper.browser.download

import net.matsudamper.browser.DownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadUniqueFileNameTest {

    @Test
    fun 重複なしの場合はそのまま返す() {
        val result = DownloadWorker.buildUniqueFileName(
            existsInDownloads = { false },
            fileName = "file.pdf",
        )
        assertEquals("file.pdf", result)
    }

    @Test
    fun 重複時は拡張子の前にカウンタを付ける() {
        val existing = setOf("file.pdf")
        val result = DownloadWorker.buildUniqueFileName(
            existsInDownloads = { it in existing },
            fileName = "file.pdf",
        )
        assertEquals("file(1).pdf", result)
    }

    @Test
    fun 複数重複時は連番で増やす() {
        val existing = setOf("file.pdf", "file(1).pdf", "file(2).pdf")
        val result = DownloadWorker.buildUniqueFileName(
            existsInDownloads = { it in existing },
            fileName = "file.pdf",
        )
        assertEquals("file(3).pdf", result)
    }

    @Test
    fun 拡張子なしのファイルでも動作する() {
        val existing = setOf("download")
        val result = DownloadWorker.buildUniqueFileName(
            existsInDownloads = { it in existing },
            fileName = "download",
        )
        assertEquals("download(1)", result)
    }

    @Test
    fun 複合拡張子でも最後のドットで分割する() {
        val existing = setOf("archive.tar.gz")
        val result = DownloadWorker.buildUniqueFileName(
            existsInDownloads = { it in existing },
            fileName = "archive.tar.gz",
        )
        assertEquals("archive.tar(1).gz", result)
    }
}

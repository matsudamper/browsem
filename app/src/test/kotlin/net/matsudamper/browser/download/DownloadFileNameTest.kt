package net.matsudamper.browser.download

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFileNameTest {
    @Test
    fun contentDispositionFileNameIsKept() {
        assertEquals(
            "archive-1.0.2.zip",
            DownloadFileName.resolve(
                urlString = "https://example.com/download.php?id=1",
                contentDisposition = "attachment; filename=\"archive-1.0.2.zip\"",
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun contentDispositionFileNameWithoutExtensionIsCompletedFromMimeType() {
        assertEquals(
            "report.pdf",
            DownloadFileName.resolve(
                urlString = "https://example.com/download.php?id=1",
                contentDisposition = "attachment; filename=report",
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun extendedContentDispositionFileNameIsDecoded() {
        assertEquals(
            "報告 書+1.pdf",
            DownloadFileName.resolve(
                urlString = "https://example.com/download.php?id=1",
                contentDisposition = "attachment; filename*=UTF-8''%E5%A0%B1%E5%91%8A%20%E6%9B%B8+1.pdf",
                mimeType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun extendedContentDispositionFileNameIsPreferredOverPlainFileName() {
        assertEquals(
            "report.pdf",
            DownloadFileName.resolve(
                urlString = "https://example.com/download.php?id=1",
                contentDisposition = "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''report.pdf",
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun inlineContentDispositionFileNameIsKept() {
        assertEquals(
            "report.pdf",
            DownloadFileName.resolve(
                urlString = "https://example.com/download.php?id=1",
                contentDisposition = "inline; filename=report.pdf",
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun genericMimeTypeDoesNotReplaceKnownFileName() {
        assertEquals(
            "tab_volume_controller-1.0.2.zip",
            DownloadFileName.resolve(
                urlString = "https://example.com/tab_volume_controller-1.0.2.zip",
                contentDisposition = null,
                mimeType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun knownUrlExtensionIsKeptWhenResponseMimeTypeDiffers() {
        assertEquals(
            "app-debug.apk.xz",
            DownloadFileName.resolve(
                urlString = "https://example.com/app-debug.apk.xz",
                contentDisposition = null,
                mimeType = "application/vnd.android.package-archive",
            ),
        )
    }

    @Test
    fun unknownUrlExtensionIsReplacedFromSpecificMimeType() {
        assertEquals(
            "download.pdf",
            DownloadFileName.resolve(
                urlString = "https://example.com/download.php?file=abc",
                contentDisposition = null,
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun generatedBinExtensionIsReplacedFromSpecificMimeType() {
        assertEquals(
            "download.pdf",
            DownloadFileName.resolve(
                urlString = "https://example.com/download",
                contentDisposition = null,
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun explicitBinExtensionInUrlIsKept() {
        assertEquals(
            "firmware.bin",
            DownloadFileName.resolve(
                urlString = "https://example.com/firmware.bin",
                contentDisposition = null,
                mimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun numericVersionSuffixIsKeptWhenMimeTypeAddsExtension() {
        assertEquals(
            "package-1.0.2.zip",
            DownloadFileName.resolve(
                urlString = "https://example.com/package-1.0.2",
                contentDisposition = null,
                mimeType = "application/zip",
            ),
        )
    }
}

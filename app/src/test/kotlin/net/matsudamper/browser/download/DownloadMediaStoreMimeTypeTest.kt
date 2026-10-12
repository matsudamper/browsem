package net.matsudamper.browser.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMediaStoreMimeTypeTest {
    @Test
    fun apkXzUsesXzMimeEvenIfResponseSaysApk() {
        // Content-Type が APK だと MediaStore が末尾に .apk を足して apk.xz.apk になる
        assertEquals(
            "application/x-xz",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "app-debug.apk.xz",
                responseMimeType = "application/vnd.android.package-archive",
            ),
        )
    }

    @Test
    fun apkUsesApkMime() {
        assertEquals(
            "application/vnd.android.package-archive",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "app-debug.apk",
                responseMimeType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun nameWithoutExtensionKeepsResponseMime() {
        assertEquals(
            "application/pdf",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "downloadfile",
                responseMimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun apkZstUsesOctetStreamToKeepExtensionOnDuplicateName() {
        // Android の MimeTypeMap が application/zstd を知らず、重複時に foo.apk.zst (1) になる
        assertEquals(
            "application/octet-stream",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "app-debug.apk.zst",
                responseMimeType = "application/vnd.android.package-archive",
            ),
        )
    }
}

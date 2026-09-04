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
}

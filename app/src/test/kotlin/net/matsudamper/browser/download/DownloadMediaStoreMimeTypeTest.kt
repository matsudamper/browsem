package net.matsudamper.browser.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMediaStoreMimeTypeTest {
    private val deviceMimeTypes = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "xz" to "application/x-xz",
    )

    @Test
    fun apkXzUsesXzMimeEvenIfResponseSaysApk() {
        // Content-Type が APK だと MediaStore が末尾に .apk を足して apk.xz.apk になる
        assertEquals(
            "application/x-xz",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "app-debug.apk.xz",
                responseMimeType = "application/vnd.android.package-archive",
                deviceMimeTypeOfExtension = deviceMimeTypes::get,
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
                deviceMimeTypeOfExtension = deviceMimeTypes::get,
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
                deviceMimeTypeOfExtension = deviceMimeTypes::get,
            ),
        )
    }

    @Test
    fun extensionUnknownToDeviceUsesOctetStream() {
        // 端末が知らない MIME を渡すと重複時に foo.apk.zst (1) になる
        assertEquals(
            "application/octet-stream",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "app-debug.apk.zst",
                responseMimeType = "application/vnd.android.package-archive",
                deviceMimeTypeOfExtension = deviceMimeTypes::get,
            ),
        )
    }

    @Test
    fun extensionKnownToDeviceUsesDeviceMime() {
        // 端末が zst を知っている場合に octet-stream を渡しても重複時に foo.apk.zst (1) になる
        assertEquals(
            "application/zstd",
            DownloadMediaStoreMimeType.fromFileName(
                fileName = "app-debug.apk.zst",
                responseMimeType = "application/vnd.android.package-archive",
                deviceMimeTypeOfExtension = (deviceMimeTypes + ("zst" to "application/zstd"))::get,
            ),
        )
    }

    @Test
    fun zstViewIntentRestoresZstdMime() {
        assertEquals(
            "application/zstd",
            DownloadMediaStoreMimeType.forViewIntent(
                fileName = "app-debug.apk (1).zst",
                mediaStoreMimeType = "application/octet-stream",
            ),
        )
    }

}

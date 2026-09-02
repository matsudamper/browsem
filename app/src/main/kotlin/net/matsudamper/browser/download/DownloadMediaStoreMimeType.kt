package net.matsudamper.browser.download

/**
 * MediaStore に保存するときの MIME タイプを決める。
 *
 * Downloads プロバイダは MIME から拡張子を補完し、DISPLAY_NAME がその拡張子で
 * 終わっていなければ末尾に足す。HTTP の Content-Type がファイル名と食い違うと
 * `foo.apk.xz` + APK MIME が `foo.apk.xz.apk` になる。
 * ファイル名に拡張子がある場合はそれを優先する。
 */
object DownloadMediaStoreMimeType {
    private const val OCTET_STREAM = "application/octet-stream"

    fun fromFileName(fileName: String, responseMimeType: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        if (extension.isEmpty() || extension == fileName.lowercase()) {
            return responseMimeType.ifBlank { OCTET_STREAM }
        }
        return mimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    private fun mimeTypeFromExtension(extension: String): String? {
        return when (extension) {
            "apk" -> "application/vnd.android.package-archive"
            "xz" -> "application/x-xz"
            "zst", "zstd" -> "application/zstd"
            "gz" -> "application/gzip"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "7z" -> "application/x-7z-compressed"
            else -> null
        }
    }
}

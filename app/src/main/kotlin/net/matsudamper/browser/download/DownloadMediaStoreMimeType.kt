package net.matsudamper.browser.download

/**
 * MediaStore に保存するときの MIME タイプを決める。
 *
 * Downloads プロバイダは MIME から拡張子を補完し、DISPLAY_NAME がその拡張子で
 * 終わっていなければ末尾に足す。HTTP の Content-Type がファイル名と食い違うと
 * `foo.apk.xz` + APK MIME が `foo.apk.xz.apk` になる。
 * ファイル名に拡張子がある場合はそれを優先する。
 *
 * 同名ファイルがあると MediaProvider は MIME と拡張子が対応するときだけ拡張子の前に
 * ` (1)` を挿入する。端末の MimeTypeMap が知らない MIME を渡すと名前全体の末尾に付き、
 * `foo.apk.zst (1)` のように拡張子が壊れるため、未登録の形式は octet-stream にする。
 */
object DownloadMediaStoreMimeType {
    private const val OCTET_STREAM = "application/octet-stream"
    private const val ZSTD = "application/zstd"

    fun fromFileName(fileName: String, responseMimeType: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        if (extension.isEmpty() || extension == fileName.lowercase()) {
            return responseMimeType.ifBlank { OCTET_STREAM }
        }
        return mimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    /**
     * zstd は MediaStore に octet-stream で保存しているため、開くときは拡張子から
     * application/zstd に戻し、それだけを受け付ける展開アプリも候補に含める
     */
    fun forViewIntent(fileName: String?, mediaStoreMimeType: String): String {
        val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return when (extension) {
            "zst", "zstd" -> ZSTD
            else -> mediaStoreMimeType
        }
    }

    private fun mimeTypeFromExtension(extension: String): String? {
        return when (extension) {
            "apk" -> "application/vnd.android.package-archive"
            "xz" -> "application/x-xz"
            "zst", "zstd" -> OCTET_STREAM
            "gz" -> "application/gzip"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "7z" -> "application/x-7z-compressed"
            else -> null
        }
    }
}

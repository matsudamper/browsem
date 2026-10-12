package net.matsudamper.browser.download

import android.webkit.MimeTypeMap

/**
 * MediaStore に保存するときの MIME タイプを決める。
 *
 * Downloads プロバイダは MIME から拡張子を補完し、DISPLAY_NAME がその拡張子で
 * 終わっていなければ末尾に足す。HTTP の Content-Type がファイル名と食い違うと
 * `foo.apk.xz` + APK MIME が `foo.apk.xz.apk` になる。
 * ファイル名に拡張子がある場合はそれを優先する。
 *
 * 同名ファイルがあると MediaProvider は端末の MimeTypeMap で拡張子と MIME が対応するときだけ
 * 拡張子の前に ` (1)` を挿入し、対応しなければ名前全体の末尾に付けて `foo.apk.zst (1)` になる。
 * 拡張子ごとの登録状況は OS バージョンで変わるため、固定表ではなく端末の MimeTypeMap に合わせ、
 * 未登録の拡張子は octet-stream にする。
 */
object DownloadMediaStoreMimeType {
    private const val OCTET_STREAM = "application/octet-stream"
    private const val ZSTD = "application/zstd"

    fun fromFileName(
        fileName: String,
        responseMimeType: String,
        deviceMimeTypeOfExtension: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
    ): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        if (extension.isEmpty() || extension == fileName.lowercase()) {
            return responseMimeType.ifBlank { OCTET_STREAM }
        }
        return deviceMimeTypeOfExtension(extension) ?: OCTET_STREAM
    }

    /**
     * zstd を登録していない端末では MediaStore に octet-stream で保存されるため、開くときは拡張子から
     * application/zstd に戻し、それだけを受け付ける展開アプリも候補に含める
     */
    fun forViewIntent(fileName: String?, mediaStoreMimeType: String): String {
        val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return when (extension) {
            "zst", "zstd" -> ZSTD
            else -> mediaStoreMimeType
        }
    }
}

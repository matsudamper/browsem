package net.matsudamper.browser.download

/** HTTPヘッダーからダウンロードのメタデータを解析するユーティリティ */
object DownloadMetadata {
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    /** Content-Type からメディアタイプ（パラメータ部を除去）を取り出す。空なら application/octet-stream */
    fun parseMimeType(contentType: String?): String {
        return contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MIME_TYPE
    }

    /** Content-Length ヘッダーを数値化する。解析できなければ -1 */
    fun parseContentLength(contentLength: String?): Long {
        return contentLength?.trim()?.toLongOrNull() ?: -1L
    }

    /**
     * Content-Range: bytes START-END/TOTAL の TOTAL を取り出す。
     * 取得できない場合（"*" やヘッダー無し）は null を返す。
     */
    fun parseTotalFromContentRange(contentRange: String?): Long? {
        return contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()
    }
}

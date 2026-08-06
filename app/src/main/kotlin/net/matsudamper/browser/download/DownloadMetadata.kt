package net.matsudamper.browser.download

/** HTTPヘッダーからダウンロードのメタデータを解析するユーティリティ */
object DownloadMetadata {
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    /**
     * HTTPステータスが存在しないことを表す値。
     *
     * GeckoView は blob: / data: / file: など HTTP 以外のチャンネルのレスポンスに対して
     * WebResponse.Builder.statusCode() を呼ばないため、statusCode が初期値の 0 のままになる。
     * （mobile/android/components/geckoview/GeckoViewStreamListener.cpp の HandleWebResponse は
     * nsIHttpChannel にキャストできた場合のみステータスコードを設定する）
     */
    const val NO_HTTP_STATUS = 0

    /**
     * レスポンスのステータスコードが成功を表すかどうかを判定する。
     *
     * [NO_HTTP_STATUS] は blob: などの非HTTPレスポンスで HTTP ステータス自体が存在しないことを
     * 意味するため、エラーではなく成功として扱う。
     */
    fun isSuccessStatus(statusCode: Int): Boolean {
        return statusCode == NO_HTTP_STATUS || statusCode in 200 until 300
    }

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

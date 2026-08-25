package net.matsudamper.browser.feature.networklog

/**
 * ページが行った通信 1 件分の記録。
 * WebExtension の webRequest から取得した情報を保持する。
 */
data class NetworkLogEntry(
    /** webRequest が採番するリクエスト ID。同一リクエストの更新判定に使う */
    val requestId: String,
    /** webRequest 上のタブ ID。対応付けできない場合は -1 */
    val tabId: Int,
    val url: String,
    val method: String,
    val resourceType: NetworkResourceType,
    /** HTTP ステータス。未受信・失敗時は 0 */
    val statusCode: Int,
    /** Content-Type ヘッダの値。未取得の場合は空文字 */
    val mimeType: String,
    /** リクエスト開始時刻（epoch ミリ秒） */
    val startedAtMillis: Long,
    val durationMillis: Long,
    /** ネットワークから実際に転送されたバイト数。キャッシュ利用時は 0 */
    val transferredBytes: Long,
    /** Content-Length ヘッダの値。不明な場合は -1 */
    val contentLengthBytes: Long,
    val fromCache: Boolean,
    /** 失敗した場合のエラー文字列。成功時は null */
    val error: String?,
    val requestHeaders: List<NetworkLogHeader>,
    val responseHeaders: List<NetworkLogHeader>,
) {
    /** 表示に使うサイズ。Content-Length を優先し、無ければ転送量を使う */
    val sizeBytes: Long
        get() = when {
            contentLengthBytes >= 0 -> contentLengthBytes
            else -> transferredBytes
        }

    /** MIME タイプのパラメータ（charset 等）を除いた本体 */
    val mimeTypeWithoutParameter: String
        get() = mimeType.substringBefore(';').trim()
}

/** ヘッダ 1 行 */
data class NetworkLogHeader(
    val name: String,
    val value: String,
)

/**
 * リソース種別。webRequest の ResourceType を UI で扱いやすい粒度にまとめたもの。
 */
enum class NetworkResourceType {
    Document,
    Stylesheet,
    Script,
    Image,
    Media,
    Font,
    Xhr,
    Other,
    ;

    companion object {
        /** webRequest の type 文字列から種別を判定する */
        fun fromWebRequestType(type: String): NetworkResourceType {
            return when (type) {
                "main_frame", "sub_frame" -> Document
                "stylesheet" -> Stylesheet
                "script" -> Script
                "image", "imageset" -> Image
                "media", "object", "object_subrequest" -> Media
                "font" -> Font
                "xmlhttprequest", "fetch", "websocket", "beacon", "ping" -> Xhr
                else -> Other
            }
        }
    }
}

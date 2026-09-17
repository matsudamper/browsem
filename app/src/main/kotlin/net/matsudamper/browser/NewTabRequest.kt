package net.matsudamper.browser

import org.mozilla.geckoview.GeckoSession

/**
 * 外部 Intent 等から新規タブを開く際の要求。
 *
 * [handedOffSession] が指定されている場合は、カスタムタブから引き渡された GeckoSession を
 * そのままタブへ載せる。読み込みが走らないため、ワンタイムトークンや POST 結果のページも
 * 表示したまま引き継げる。
 *
 * [sessionState] は引き渡されたセッションが閉じている（コンテンツプロセスの停止後など）
 * ときの復元に使う。
 */
internal data class NewTabRequest(
    val url: String,
    val handedOffSession: GeckoSession? = null,
    val sessionState: String? = null,
    val referrerUrl: String? = null,
)

package net.matsudamper.browser

/**
 * 外部 Intent 等から新規タブを開く際の要求。
 *
 * [sessionState] が指定されている場合は、カスタムタブからの引き継ぎとして
 * GeckoSession の状態（履歴・スクロール位置など）を復元する。
 */
internal data class NewTabRequest(
    val url: String,
    val sessionState: String? = null,
)

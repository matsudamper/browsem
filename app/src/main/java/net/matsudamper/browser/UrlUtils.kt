package net.matsudamper.browser

import android.content.Intent
import java.net.URLEncoder

/**
 * ユーザー入力を閲覧可能な URL に変換する。
 *
 * - 空白 → ホームページ URL
 * - http:// / https:// で始まる → そのまま返す
 * - スペースなし＆ドットあり → https:// を付与してドメインとして扱う
 * - それ以外 → searchTemplate の %s を検索クエリに置換して検索 URL を生成する
 */
internal fun buildUrlFromInput(
    input: String,
    homepageUrl: String,
    searchTemplate: String,
): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return homepageUrl
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (!trimmed.contains(" ") && trimmed.contains(".")) return "https://$trimmed"
    return searchTemplate.replace("%s", URLEncoder.encode(trimmed, "UTF-8"))
}

internal fun Intent.resolveWebUrl(): String? {
    if (action != Intent.ACTION_VIEW) return null
    val data = data ?: return null
    val scheme = data.scheme ?: return null
    if (scheme != "http" && scheme != "https") return null
    return data.toString()
}

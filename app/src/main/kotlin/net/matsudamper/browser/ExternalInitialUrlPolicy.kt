package net.matsudamper.browser

import java.util.Locale

/**
 * 外部から渡された初期 URL のうち、ブラウザで開いてよいものを判定する。
 *
 * exported な Activity は明示 Intent を投げられると intent-filter のスキーム制限が効かないため、
 * 他アプリから file:// や content:// を開かせられないようここで http/https に限定する。
 */
object ExternalInitialUrlPolicy {
    fun sanitize(url: String?): String? {
        if (url == null) return null
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        return url
    }
}

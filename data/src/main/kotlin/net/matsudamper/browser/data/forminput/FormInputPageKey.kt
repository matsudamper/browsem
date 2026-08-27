package net.matsudamper.browser.data.forminput

import android.net.Uri

/**
 * フォーム入力の保存・照合に使うページキー。
 * host と path の完全一致でマッチする。
 */
data class FormInputPageKey(
    val host: String,
    val path: String,
)

fun parseFormInputPageKey(url: String): FormInputPageKey? {
    if (url.isBlank()) return null
    val uri = Uri.parse(url)
    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty()) return null
    val rawPath = uri.encodedPath.orEmpty()
    val path = when {
        rawPath.isEmpty() || rawPath == "/" -> ""
        else -> rawPath.removeSuffix("/")
    }
    return FormInputPageKey(host = host, path = path)
}

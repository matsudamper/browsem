package net.matsudamper.browser.data.forminput

import android.net.Uri

/**
 * フォーム入力の保存・照合に使う web origin。
 */
data class FormInputOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

/**
 * フォーム入力の保存・照合に使うページキー。
 * scheme・host・port・path の完全一致でマッチする。
 */
data class FormInputPageKey(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    fun origin(): FormInputOrigin = FormInputOrigin(scheme = scheme, host = host, port = port)
}

fun parseFormInputPageKey(url: String): FormInputPageKey? {
    if (url.isBlank()) return null
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty()) return null
    val port = effectiveFormInputPort(uri)
    if (port < 0) return null
    val rawPath = uri.encodedPath.orEmpty()
    val path = when {
        rawPath.isEmpty() || rawPath == "/" -> ""
        else -> rawPath
    }
    return FormInputPageKey(scheme = scheme, host = host, port = port, path = path)
}

fun effectiveFormInputPort(uri: Uri): Int {
    if (uri.port != -1) return uri.port
    return when (uri.scheme?.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}

fun displayFormInputOrigin(origin: FormInputOrigin): String {
    val defaultPort = when (origin.scheme.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> origin.port
    }
    return if (origin.port == defaultPort) {
        "${origin.scheme}://${origin.host}"
    } else {
        "${origin.scheme}://${origin.host}:${origin.port}"
    }
}

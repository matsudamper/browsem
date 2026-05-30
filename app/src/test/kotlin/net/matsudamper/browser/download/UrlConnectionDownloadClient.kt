package net.matsudamper.browser.download

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * テスト用: HttpURLConnection を用いた DownloadHttpClient 実装。
 * 本番の GeckoWebExecutor の代わりに、ローカルHTTPサーバーへ実際にHTTPリクエストを送る。
 * リダイレクトは手動で追従する（最大段数・相対Location対応）。
 */
class UrlConnectionDownloadClient(
    private val maxRedirects: Int = MAX_REDIRECTS,
) : DownloadHttpClient {

    override fun fetch(url: String, referrerUrl: String, rangeStart: Long): DownloadHttpResponse {
        return open(url, "GET", referrerUrl, rangeStart, postBody = null, redirects = 0)
    }

    /** フォーム submit(POST) を模したリクエスト。パスワード送信→ダウンロード開始の再現に用いる */
    fun fetchPost(url: String, formData: ByteArray, referrerUrl: String = ""): DownloadHttpResponse {
        return open(url, "POST", referrerUrl, rangeStart = 0L, postBody = formData, redirects = 0)
    }

    private fun open(
        url: String,
        method: String,
        referrerUrl: String,
        rangeStart: Long,
        postBody: ByteArray?,
        redirects: Int,
    ): DownloadHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            if (referrerUrl.isNotBlank()) setRequestProperty("Referer", referrerUrl)
            if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            if (postBody != null) {
                doOutput = true
                outputStream.use { it.write(postBody) }
            }
        }

        val status = connection.responseCode
        if (status in REDIRECT_CODES && redirects < maxRedirects) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location != null) {
                // 相対Locationにも対応する。リダイレクト後は GET で取得する
                val next = URL(URL(url), location).toString()
                return open(next, "GET", referrerUrl, rangeStart, postBody = null, redirects = redirects + 1)
            }
        }
        return UrlConnectionDownloadResponse(connection, url)
    }

    private companion object {
        const val MAX_REDIRECTS = 20
        const val TIMEOUT_MILLIS = 10_000
        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307, // Temporary Redirect（HttpURLConnection に定数なし）
            308, // Permanent Redirect（HttpURLConnection に定数なし）
        )
    }
}

private class UrlConnectionDownloadResponse(
    private val connection: HttpURLConnection,
    override val finalUrl: String,
) : DownloadHttpResponse {
    override val statusCode: Int = connection.responseCode
    override val body: InputStream? =
        if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream

    override fun header(name: String): String? = connection.getHeaderField(name)

    override fun close() {
        runCatching { body?.close() }
        connection.disconnect()
    }
}

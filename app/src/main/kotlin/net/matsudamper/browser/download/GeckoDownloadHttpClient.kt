package net.matsudamper.browser.download

import java.io.IOException
import java.io.InputStream
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

/** GeckoViewのWebResponseをDownloadHttpResponseとして扱うアダプタ */
class WebResponseDownloadResponse(private val response: WebResponse) : DownloadHttpResponse {
    override val statusCode: Int get() = response.statusCode
    override val finalUrl: String get() = response.uri
    override val body: InputStream? get() = response.body

    override fun header(name: String): String? {
        // WebResponse.headers は大文字小文字を区別しないMap
        return response.headers[name]
    }

    override fun close() {
        response.body?.close()
    }
}

/**
 * GeckoWebExecutorを用いたDownloadHttpClient実装。
 * GeckoViewのCookie/セッション情報を共有してリクエストするため、ログイン後のダウンロード等で
 * セッションを引き継げる。
 */
class GeckoDownloadHttpClient(
    private val geckoRuntime: GeckoRuntime,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : DownloadHttpClient {
    override fun fetch(url: String, referrerUrl: String, rangeStart: Long): DownloadHttpResponse {
        val executor = GeckoWebExecutor(geckoRuntime)
        val request = WebRequest.Builder(url)
            .apply {
                // Referer は forbidden header のため addHeader では Gecko に除去される。
                // 専用の referrer API を使わないとホットリンク保護のあるサーバーで 403 になる
                if (referrerUrl.isNotBlank()) referrer(referrerUrl)
                if (rangeStart > 0) addHeader("Range", "bytes=$rangeStart-")
            }
            .build()
        val response = try {
            executor.fetch(request).poll(timeoutMillis)
                ?: throw IOException("レスポンスがnullです。")
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("Geckoリクエスト失敗", e)
        }
        return WebResponseDownloadResponse(response)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}

package net.matsudamper.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream

internal class GeckoDownloadManager(
    private val context: Context,
    private val runtime: GeckoRuntime,
) {
    // GeckoViewからのWebResponseを直接保存する（ダウンロードリンクのクリック時に使用）
    suspend fun saveFileFromResponse(response: WebResponse) {
        withContext(Dispatchers.IO) {
            val body = response.body ?: throw IOException("Response body is empty.")
            val contentDisposition = response.headers["Content-Disposition"]
            val mimeType = response.headers["Content-Type"]
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "application/octet-stream"
            val fileName = URLUtil
                .guessFileName(response.uri, contentDisposition, mimeType)
                .ifBlank { "download-${System.currentTimeMillis()}" }
            body.use { input ->
                saveFileToDownloads(
                    inputStream = input,
                    fileName = fileName,
                    mimeType = mimeType,
                )
            }
        }
    }

    // 画像URLを指定してダウンロードする（コンテキストメニュー経由の画像保存時に使用）
    suspend fun downloadImageWithSession(
        imageUrl: String,
        referrerUrl: String,
    ) {
        withContext(Dispatchers.IO) {
            if (!imageUrl.isHttpOrHttps()) {
                throw IOException("Unsupported image URL scheme.")
            }
            val requestBuilder = WebRequest.Builder(imageUrl)
            if (referrerUrl.isNotBlank() && referrerUrl.isHttpOrHttps()) {
                requestBuilder.referrer(referrerUrl)
            }
            val response = GeckoWebExecutor(runtime)
                .fetch(requestBuilder.build())
                .awaitBlocking()
            val statusCode = response.statusCode
            if (statusCode !in 200..299 && statusCode != 0) {
                throw IOException("Unexpected HTTP status: $statusCode")
            }
            val responseBody = response.body ?: throw IOException("Response body is empty.")
            responseBody.use { input ->
                val contentDisposition = response.headers["Content-Disposition"]
                val mimeType = response.headers["Content-Type"]
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "image/*"
                val fileName = URLUtil
                    .guessFileName(imageUrl, contentDisposition, mimeType)
                    .ifBlank { "image-${System.currentTimeMillis()}" }
                saveFileToDownloads(
                    inputStream = input,
                    fileName = fileName,
                    mimeType = mimeType,
                )
            }
        }
    }

    private fun saveFileToDownloads(
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create download entry.")
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            } ?: throw IOException("Failed to open output stream.")

            val completeValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, completeValues, null, null)
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }
}

private fun String.isHttpOrHttps(): Boolean {
    return when (Uri.parse(this).scheme?.lowercase()) {
        "http", "https" -> true
        else -> false
    }
}

private fun <T : Any> GeckoResult<T>.awaitBlocking(): T {
    val value = try {
        poll(60_000)
    } catch (throwable: Throwable) {
        throw IOException("Gecko request failed.", throwable)
    }
    return value ?: throw IOException("Response is null.")
}

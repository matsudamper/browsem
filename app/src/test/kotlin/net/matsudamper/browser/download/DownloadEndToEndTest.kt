package net.matsudamper.browser.download

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import net.matsudamper.browser.download.LocalDownloadServer.Companion.respondChunked
import net.matsudamper.browser.download.LocalDownloadServer.Companion.respondFixed
import net.matsudamper.browser.download.LocalDownloadServer.Companion.respondNoBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ローカルHTTPサーバーに対して、様々なダウンロード実装を再現するエンドツーエンドテスト。
 * 本番Workerと同じコア（DownloadEngine.copyTo / DownloadMetadata）を通して保存し、
 * 0バイトになるケースを炙り出す。
 */
class DownloadEndToEndTest {

    private lateinit var server: LocalDownloadServer
    private val client = UrlConnectionDownloadClient()
    private val engine = DownloadEngine()

    @Before
    fun setUp() {
        server = LocalDownloadServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private class SaveResult(val statusCode: Int, val bytes: ByteArray, val failed: Boolean)

    /** WorkerのdownloadFileと同じ標準フロー（ステータス確認→body→copyTo）でURLを保存する */
    private suspend fun fetchAndSave(path: String): SaveResult {
        client.fetch(server.baseUrl + path, "", 0L).use { response ->
            if (response.statusCode !in 200 until 300) {
                return SaveResult(response.statusCode, ByteArray(0), failed = true)
            }
            val body = response.body ?: return SaveResult(response.statusCode, ByteArray(0), failed = true)
            val out = ByteArrayOutputStream()
            val contentLength = DownloadMetadata.parseContentLength(response.header("Content-Length"))
            engine.copyTo(body, out, contentLength)
            return SaveResult(response.statusCode, out.toByteArray(), failed = false)
        }
    }

    @Test
    fun normalGetDownload() = runTest {
        val payload = "%PDF-1.4 normal download body".toByteArray()
        server.handle("/file.pdf") { ex ->
            ex.respondFixed(200, payload, mapOf("Content-Type" to "application/pdf"))
        }

        val result = fetchAndSave("/file.pdf")

        assertEquals(200, result.statusCode)
        assertArrayEquals(payload, result.bytes)
    }

    @Test
    fun chunkedWithoutContentLength() = runTest {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        server.handle("/chunked") { ex -> ex.respondChunked(200, payload) }

        val result = fetchAndSave("/chunked")

        assertArrayEquals(payload, result.bytes)
    }

    @Test
    fun followsRedirect() = runTest {
        val payload = "redirected-content".toByteArray()
        server.handle("/target") { ex -> ex.respondFixed(200, payload) }
        server.handle("/redirect") { ex -> ex.respondNoBody(302, mapOf("Location" to "/target")) }

        val result = fetchAndSave("/redirect")

        assertArrayEquals(payload, result.bytes)
    }

    @Test
    fun followsRedirectChain() = runTest {
        val payload = "deep-target-data".toByteArray()
        server.handle("/r1") { ex -> ex.respondNoBody(301, mapOf("Location" to "/r2")) }
        server.handle("/r2") { ex -> ex.respondNoBody(302, mapOf("Location" to "/r3")) }
        server.handle("/r3") { ex -> ex.respondNoBody(307, mapOf("Location" to "/final")) }
        server.handle("/final") { ex -> ex.respondFixed(200, payload) }

        val result = fetchAndSave("/r1")

        assertArrayEquals(payload, result.bytes)
    }

    @Test
    fun exposesContentDispositionHeader() = runTest {
        val payload = "x".toByteArray()
        server.handle("/dl") { ex ->
            ex.respondFixed(200, payload, mapOf("Content-Disposition" to "attachment; filename=\"report.pdf\""))
        }

        client.fetch(server.baseUrl + "/dl", "", 0L).use { response ->
            assertEquals("attachment; filename=\"report.pdf\"", response.header("Content-Disposition"))
        }
    }

    @Test
    fun emptyBodyWithoutContentLengthIsZeroByteSuccess() = runTest {
        // Content-Length 不明で本当に空のレスポンスは 0 バイト成功（正当な空ファイルと区別できない）
        server.handle("/empty") { ex -> ex.respondChunked(200, ByteArray(0)) }

        val result = fetchAndSave("/empty")

        assertEquals(0, result.bytes.size)
        assertFalse(result.failed)
    }

    @Test
    fun contentLengthMismatchFailsAsTruncated() = runTest {
        // Content-Length: 1000 と宣言しつつ 400 バイトで切断 → 切断検出で失敗する
        server.handle("/truncated") { ex ->
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, 1000)
            ex.responseBody.write(ByteArray(400))
            // 残り 600 バイトを書かずに終了（finally で close）
        }

        var failed = false
        try {
            fetchAndSave("/truncated")
        } catch (e: Exception) {
            failed = true
        }

        assertTrue("Content-Length 未達は失敗として扱われるべき", failed)
    }

    @Test
    fun passwordSubmitPostBodyIsSavedWhileGetRefetchReturnsZeroBytes() = runTest {
        // パスワード submit(POST) でのみ実データが返り、URLを GET し直すと空になるエンドポイント。
        // これがユーザー報告の「パスワード入れて submit してダウンロード開始されるタイプ」の再現。
        val pdf = "%PDF-1.4 secret report after login".toByteArray()
        server.handle("/download") { ex ->
            if (ex.requestMethod == "POST") {
                ex.respondFixed(200, pdf, mapOf("Content-Type" to "application/pdf"))
            } else {
                // URL再取得（GET）ではログインページ相当の空レスポンス
                ex.respondChunked(200, ByteArray(0))
            }
        }

        // 旧実装の再現: URLをGETで再取得 → 0バイト（これがバグ）
        val refetched = fetchAndSave("/download")
        assertEquals("URL再取得(GET)では0バイトになる", 0, refetched.bytes.size)

        // 新実装: POSTで得たレスポンスのボディを直接保存 → 実データが保存される
        client.fetchPost(server.baseUrl + "/download", "password=secret".toByteArray()).use { response ->
            assertEquals(200, response.statusCode)
            val out = ByteArrayOutputStream()
            val contentLength = DownloadMetadata.parseContentLength(response.header("Content-Length"))
            engine.copyTo(response.body!!, out, contentLength)
            assertArrayEquals("ボディ直接保存なら実データが保存される", pdf, out.toByteArray())
        }
    }

    @Test
    fun serverError500Fails() = runTest {
        server.handle("/error") { ex -> ex.respondNoBody(500) }

        val result = fetchAndSave("/error")

        assertTrue(result.failed)
        assertEquals(500, result.statusCode)
    }

    @Test
    fun rangeResumeContinuesFromOffset() = runTest {
        val full = ByteArray(1000) { (it % 256).toByte() }
        server.handle("/range") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val start = range.removePrefix("bytes=").substringBefore("-").toInt()
                val part = full.copyOfRange(start, full.size)
                ex.responseHeaders.add("Content-Range", "bytes $start-${full.size - 1}/${full.size}")
                ex.sendResponseHeaders(206, part.size.toLong())
                ex.responseBody.write(part)
            } else {
                ex.respondFixed(200, full)
            }
        }

        client.fetch(server.baseUrl + "/range", "", 400L).use { response ->
            assertEquals(206, response.statusCode)
            val total = DownloadMetadata.parseTotalFromContentRange(response.header("Content-Range"))
            assertEquals(1000L, total)
            val out = ByteArrayOutputStream()
            engine.copyTo(response.body!!, out, total ?: -1L, startBytes = 400L)
            assertArrayEquals(full.copyOfRange(400, 1000), out.toByteArray())
        }
    }
}

package net.matsudamper.browser

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * androidTest のテスト用ページをループバック(127.0.0.1)の HTTP で配信する簡易サーバー。
 *
 * GeckoView 153 (Firefox 153 / Bugzilla 2034168) から拡張機能の file: へのアクセスは
 * 明示的なオプトイン制になり、`<all_urls>` のホスト権限だけでは file URL に
 * コンテンツスクリプトが注入されなくなった。GeckoView にはオプトインを与える
 * 公開 API が無いため、ビルトイン拡張のブリッジ(テーマカラー・メディア)を検証する
 * テストは file URL ではなく HTTP でページを配信する。
 *
 * 外部ネットワークへ依存させないという既存テストの方針は維持するため、
 * 待ち受けはループバックアドレスに限定する。
 */
internal class LocalHttpServer(rootDir: File) : AutoCloseable {
    private val root: File = rootDir.canonicalFile
    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var closed = false

    val port: Int get() = serverSocket.localPort

    init {
        executor.execute { acceptLoop() }
    }

    /**
     * ルートディレクトリ配下のファイルを指す HTTP URL を返す。
     */
    fun url(path: String): String = "http://$LOOPBACK_HOST:$port/${path.removePrefix("/")}"

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                return
            }
            // シャットダウン中は RejectedExecutionException になるため握り潰す
            runCatching {
                executor.execute {
                    socket.use { runCatching { handleConnection(it) } }
                }
            }.onFailure { runCatching { socket.close() } }
        }
    }

    private fun handleConnection(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val requestLine = readAsciiLine(input) ?: return
        val headers = mutableListOf<String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            headers += line
        }

        val output = socket.getOutputStream().buffered()
        val tokens = requestLine.split(' ')
        val method = tokens.getOrNull(0).orEmpty()
        val target = tokens.getOrNull(1).orEmpty()
        if (method != "GET" && method != "HEAD") {
            writeStatusOnly(output, "405 Method Not Allowed")
            return
        }

        val file = resolveFile(target)
        if (file == null) {
            writeStatusOnly(output, "404 Not Found")
            return
        }
        writeFile(output, file, rangeHeaderOf(headers), includeBody = method == "GET")
    }

    /**
     * リクエストターゲットをルート配下の実ファイルへ解決する。
     * ルート外へ抜けるパスは解決しない。
     */
    private fun resolveFile(target: String): File? {
        val path = URLDecoder.decode(target.substringBefore('?'), Charsets.UTF_8.name())
        val file = File(root, path.removePrefix("/")).canonicalFile
        val insideRoot = file.path == root.path || file.path.startsWith(root.path + File.separator)
        return file.takeIf { insideRoot && it.isFile }
    }

    private fun rangeHeaderOf(headers: List<String>): String? {
        return headers
            .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    /**
     * Range 指定があれば 206、無ければ 200 でファイルを返す。
     * 動画のシーク(playlist.html は currentTime を先頭以外へ移動する)で
     * Range リクエストが飛ぶため、部分応答へ対応する。
     */
    private fun writeFile(output: OutputStream, file: File, rangeHeader: String?, includeBody: Boolean) {
        val fileLength = file.length()
        val range = parseRange(rangeHeader, fileLength)
        val start = range?.first ?: 0L
        val endInclusive = range?.second ?: (fileLength - 1).coerceAtLeast(0L)
        val contentLength = if (fileLength == 0L) 0L else endInclusive - start + 1

        val status = if (range == null) "200 OK" else "206 Partial Content"
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(CRLF)
            append("Content-Type: ").append(contentTypeOf(file)).append(CRLF)
            append("Content-Length: ").append(contentLength).append(CRLF)
            append("Accept-Ranges: bytes").append(CRLF)
            if (range != null) {
                append("Content-Range: bytes ").append(start).append('-')
                    .append(endInclusive).append('/').append(fileLength).append(CRLF)
            }
            append("Cache-Control: no-store").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))

        if (includeBody && contentLength > 0) {
            RandomAccessFile(file, "r").use { source ->
                source.seek(start)
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        output.flush()
    }

    private fun writeStatusOnly(output: OutputStream, status: String) {
        val header = "HTTP/1.1 $status${CRLF}Content-Length: 0${CRLF}Connection: close$CRLF$CRLF"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * `bytes=start-end` 形式のみを解釈する。解釈できない場合は null を返し、全体を返す。
     */
    private fun parseRange(rangeHeader: String?, fileLength: Long): Pair<Long, Long>? {
        if (rangeHeader == null || fileLength <= 0) return null
        val spec = rangeHeader.substringAfter("bytes=", "").substringBefore(',')
        if (spec.isEmpty() || !spec.contains('-')) return null
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-').trim()
        val start = startText.toLongOrNull()
        val end = endText.toLongOrNull()
        return when {
            start != null -> {
                if (start >= fileLength) return null
                start to (end ?: (fileLength - 1)).coerceAtMost(fileLength - 1)
            }
            // `bytes=-N` は末尾 N バイト
            end != null -> (fileLength - end).coerceAtLeast(0L) to fileLength - 1
            else -> null
        }
    }

    private fun contentTypeOf(file: File): String {
        return CONTENT_TYPES[file.extension.lowercase()] ?: "application/octet-stream"
    }

    /**
     * ヘッダ行を 1 行読む。ストリーム終端では null を返す。
     */
    private fun readAsciiLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return builder.takeIf { it.isNotEmpty() }?.toString()
            if (value == '\n'.code) return builder.toString().removeSuffix("\r")
            builder.append(value.toChar())
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val BACKLOG = 16
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private const val CRLF = "\r\n"
        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "webm" to "video/webm",
            "mp4" to "video/mp4",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "svg" to "image/svg+xml",
        )
    }
}

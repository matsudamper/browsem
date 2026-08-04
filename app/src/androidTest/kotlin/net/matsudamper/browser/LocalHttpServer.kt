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
    private val openSockets = mutableSetOf<Socket>()

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
        // 応答の書き込みはソケットの soTimeout の対象外で、
        // クライアントが読み取りを止めるとハンドラがブロックしたまま残る。
        // shutdownNow() では解除できないため、接続中のソケットを明示的に閉じる。
        synchronized(openSockets) {
            openSockets.forEach { runCatching { it.close() } }
            openSockets.clear()
        }
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                return
            }
            synchronized(openSockets) { openSockets.add(socket) }
            // シャットダウン中は RejectedExecutionException になるため握り潰す
            runCatching {
                executor.execute {
                    try {
                        socket.use { runCatching { handleConnection(it) } }
                    } finally {
                        synchronized(openSockets) { openSockets.remove(socket) }
                    }
                }
            }.onFailure {
                synchronized(openSockets) { openSockets.remove(socket) }
                runCatching { socket.close() }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
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
        } else {
            writeFile(output, file, rangeHeaderOf(headers), includeBody = method == "GET")
        }
        finishResponse(socket, input)
    }

    /**
     * 応答を書き終えた側から先に close すると、未読データが残っている場合に
     * RST となり応答が破棄されることがある。書き込み方向だけ閉じ、
     * 相手が閉じるまで読み捨ててから接続を終える。
     */
    private fun finishResponse(socket: Socket, input: InputStream) {
        runCatching { socket.shutdownOutput() }
        runCatching {
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (input.read(buffer) >= 0) {
                // 読み捨てる
            }
        }
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
     * 満たせない Range は 416 を返す。
     */
    private fun writeFile(output: OutputStream, file: File, rangeHeader: String?, includeBody: Boolean) {
        val fileLength = file.length()
        val range = parseRange(rangeHeader, fileLength)
        if (range is RangeResult.Unsatisfiable) {
            writeRangeNotSatisfiable(output, fileLength)
            return
        }

        val satisfiable = range as? RangeResult.Satisfiable
        val start = satisfiable?.start ?: 0L
        val endInclusive = satisfiable?.endInclusive ?: (fileLength - 1).coerceAtLeast(0L)
        val contentLength = if (fileLength == 0L) 0L else endInclusive - start + 1

        val status = if (satisfiable == null) "200 OK" else "206 Partial Content"
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(CRLF)
            append("Content-Type: ").append(contentTypeOf(file)).append(CRLF)
            append("Content-Length: ").append(contentLength).append(CRLF)
            append("Accept-Ranges: bytes").append(CRLF)
            if (satisfiable != null) {
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

    private fun writeRangeNotSatisfiable(output: OutputStream, fileLength: Long) {
        val header = buildString {
            append("HTTP/1.1 416 Range Not Satisfiable").append(CRLF)
            append("Content-Range: bytes */").append(fileLength).append(CRLF)
            append("Content-Length: 0").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun writeStatusOnly(output: OutputStream, status: String) {
        val header = "HTTP/1.1 $status${CRLF}Content-Length: 0${CRLF}Connection: close$CRLF$CRLF"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * `bytes=start-end` 形式のみを解釈する。
     * Range 指定なし(全体を返す)と、指定はあるが満たせない(416)を区別して返す。
     */
    private fun parseRange(rangeHeader: String?, fileLength: Long): RangeResult {
        if (rangeHeader == null) return RangeResult.None
        val spec = rangeHeader.substringAfter("bytes=", "").substringBefore(',')
        if (spec.isEmpty() || !spec.contains('-')) return RangeResult.None
        if (fileLength <= 0) return RangeResult.Unsatisfiable
        val start = spec.substringBefore('-').trim().toLongOrNull()
        val end = spec.substringAfter('-').trim().toLongOrNull()
        return when {
            start != null -> {
                val endInclusive = (end ?: (fileLength - 1)).coerceAtMost(fileLength - 1)
                if (start >= fileLength || endInclusive < start) {
                    RangeResult.Unsatisfiable
                } else {
                    RangeResult.Satisfiable(start, endInclusive)
                }
            }
            // `bytes=-N` は末尾 N バイト
            end != null -> {
                if (end <= 0) {
                    RangeResult.Unsatisfiable
                } else {
                    RangeResult.Satisfiable((fileLength - end).coerceAtLeast(0L), fileLength - 1)
                }
            }
            else -> RangeResult.None
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

    /**
     * Range ヘッダの解釈結果。
     */
    private sealed interface RangeResult {
        /** Range 指定なし。全体を 200 で返す。 */
        data object None : RangeResult

        /** Range 指定はあるが満たせない。416 を返す。 */
        data object Unsatisfiable : RangeResult

        data class Satisfiable(val start: Long, val endInclusive: Long) : RangeResult
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val BACKLOG = 16
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private const val SOCKET_TIMEOUT_MS = 10_000
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

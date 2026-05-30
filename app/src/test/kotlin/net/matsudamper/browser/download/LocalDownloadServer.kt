package net.matsudamper.browser.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * テスト用のローカルHTTPサーバー。
 * リダイレクト・chunked・Range・POST必須など、様々なダウンロード実装を再現するために使う。
 */
class LocalDownloadServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** 割り当てられたポートを含むベースURL */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    /** パスにハンドラを登録する。ハンドラ例外時もExchangeを確実にクローズする */
    fun handle(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
    }

    companion object {
        /** Content-Length 固定長レスポンスを送る */
        fun HttpExchange.respondFixed(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            headers.forEach { (k, v) -> responseHeaders.add(k, v) }
            sendResponseHeaders(status, body.size.toLong())
            responseBody.write(body)
        }

        /** chunked（Content-Length なし）でレスポンスを送る */
        fun HttpExchange.respondChunked(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            headers.forEach { (k, v) -> responseHeaders.add(k, v) }
            // responseLength == 0 で chunked transfer encoding になる
            sendResponseHeaders(status, 0)
            responseBody.write(body)
        }

        /** ヘッダーのみ（ボディ無し）でレスポンスを送る。リダイレクトやエラーに用いる */
        fun HttpExchange.respondNoBody(status: Int, headers: Map<String, String> = emptyMap()) {
            headers.forEach { (k, v) -> responseHeaders.add(k, v) }
            sendResponseHeaders(status, -1)
        }
    }
}

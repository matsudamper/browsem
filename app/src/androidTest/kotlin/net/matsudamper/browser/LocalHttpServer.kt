package net.matsudamper.browser

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * androidTest のテスト用ページをループバック(127.0.0.1)の HTTP で配信する Ktor サーバー。
 *
 * GeckoView 153 (Firefox 153 / Bugzilla 2034168) から拡張機能の file: へのアクセスは
 * 明示的なオプトイン制になり、`<all_urls>` のホスト権限だけでは file URL に
 * コンテンツスクリプトが注入されなくなった。GeckoView にはオプトインを与える
 * 公開 API が無いため、ビルトイン拡張のブリッジ(テーマカラー・メディア)を検証する
 * テストは file URL ではなく HTTP でページを配信する。
 *
 * 外部ネットワークへ依存させないという既存テストの方針は維持するため、
 * 待ち受けはループバックアドレスに限定する。
 * 動画のシーク(playlist.html は currentTime を先頭以外へ移動する)で Range
 * リクエストが飛ぶため、PartialContent を有効にする。
 */
internal class LocalHttpServer(rootDir: File) : AutoCloseable {
    private val server = embeddedServer(CIO, host = LOOPBACK_HOST, port = ANY_PORT) {
        install(PartialContent)
        routing {
            staticFiles("/", rootDir)
        }
    }.start(wait = false)

    /**
     * ポートは OS 任せのため、bind 完了後に解決された値を使う。
     *
     * `resolvedConnectors()` は bind 完了まで suspend するため、待ち時間に上限を設ける。
     * また、この初期化子が例外を投げるとコンストラクタが失敗して呼び出し側が
     * インスタンスを受け取れず `close()` されないため、その場でエンジンを停止する。
     */
    private val port: Int = runBlocking {
        runCatching {
            withTimeout(BIND_TIMEOUT_MS) {
                server.engine.resolvedConnectors().first().port
            }
        }.getOrElse { error ->
            server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
            throw error
        }
    }

    /**
     * ルートディレクトリ配下のファイルを指す HTTP URL を返す。
     */
    fun url(path: String): String = "http://$LOOPBACK_HOST:$port/${path.removePrefix("/")}"

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val ANY_PORT = 0
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val BIND_TIMEOUT_MS = 10_000L
    }
}

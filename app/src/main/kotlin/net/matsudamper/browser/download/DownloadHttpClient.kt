package net.matsudamper.browser.download

import java.io.InputStream

/**
 * ダウンロードのHTTPレスポンスを表す抽象。
 * GeckoViewのWebResponseや、テスト用のHTTPクライアント実装を同一インターフェースで扱う。
 */
interface DownloadHttpResponse : AutoCloseable {
    /** HTTPステータスコード */
    val statusCode: Int

    /** リダイレクト後の最終URL（ファイル名推測に使用） */
    val finalUrl: String

    /** レスポンスボディ。null の場合はボディ無し */
    val body: InputStream?

    /** 指定ヘッダーの値を取得する（大文字小文字を区別しない）。複数ある場合は最初の値 */
    fun header(name: String): String?

    /** ボディを確実にクローズする */
    override fun close()
}

/**
 * ダウンロード用のHTTPクライアント抽象。
 * 本番はGeckoWebExecutorを用いた実装、テストはHttpURLConnectionを用いた実装を注入する。
 */
fun interface DownloadHttpClient {
    /**
     * 指定URLをGET取得する。
     * @param rangeStart 0より大きい場合 Range: bytes=rangeStart- を付与して途中から取得する
     */
    fun fetch(url: String, referrerUrl: String, rangeStart: Long): DownloadHttpResponse
}

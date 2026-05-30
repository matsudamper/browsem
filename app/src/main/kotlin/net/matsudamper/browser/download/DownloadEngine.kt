package net.matsudamper.browser.download

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** ダウンロードが途中で切断され、期待バイト数に達しなかったことを表す例外 */
class DownloadTruncatedException(
    val totalRead: Long,
    val expectedLength: Long,
) : IOException("ダウンロードが途中で切断されました ($totalRead / $expectedLength bytes)")

/**
 * ダウンロードのコアロジック。Android非依存でJVMテスト可能。
 * HTTPレスポンスのボディをシンクへコピーし、進捗通知と切断検出を行う。
 *
 * 進捗通知のレート制限は呼び出し側（Worker）の責務とする。ここでは読み取りのたびに onProgress を呼ぶ。
 */
class DownloadEngine {
    /**
     * body を sink にコピーする。
     *
     * @param expectedTotalLength 期待される総バイト数（startBytes を含む）。不明な場合は -1。
     *   0より大きく、かつ読み取り総量がこれ未満で終端に達した場合は切断とみなして例外を投げる。
     * @param startBytes 再開時に既に書き込み済みのバイト数。進捗計算の起点。
     * @param onProgress 進捗コールバック。引数は startBytes を含む累計読み取りバイト数。
     *   読み取りのたびに呼ばれるため、重い処理は呼び出し側でレート制限すること。
     * @return 書き込み完了後の累計バイト数（startBytes を含む）
     * @throws DownloadTruncatedException 期待バイト数に達せず終端に達した場合
     */
    suspend fun copyTo(
        body: InputStream,
        sink: OutputStream,
        expectedTotalLength: Long,
        startBytes: Long = 0L,
        onProgress: suspend (totalRead: Long) -> Unit = {},
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalRead = startBytes
        var bytesRead: Int
        while (body.read(buffer).also { bytesRead = it } != -1) {
            sink.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            onProgress(totalRead)
        }
        // 期待総量が判明していて、それに満たないまま終端に達した場合は切断とみなす。
        // これにより Content-Length あり・本文途中切断（0バイト含む）を「成功」ではなく失敗として扱える。
        if (expectedTotalLength > 0 && totalRead < expectedTotalLength) {
            throw DownloadTruncatedException(totalRead = totalRead, expectedLength = expectedTotalLength)
        }
        return totalRead
    }

    companion object {
        const val BUFFER_SIZE = 8192
    }
}

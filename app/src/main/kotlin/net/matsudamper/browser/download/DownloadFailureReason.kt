package net.matsudamper.browser.download

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ダウンロード失敗の例外を、UI・通知に表示できる日本語のメッセージへ変換するユーティリティ。
 *
 * 失敗表示が「失敗」だけでは原因が分からず、再試行すべきか（通信不良）
 * それとも再試行しても直らないか（blob URL の期限切れ等）をユーザーが判断できないため、
 * 例外の内容を必ず1行のメッセージに落として保存する。Android非依存でJVMテスト可能。
 */
object DownloadFailureReason {

    /** 原因を特定できない例外に使うメッセージ */
    const val UNKNOWN = "不明なエラーが発生しました"

    fun from(error: Throwable): String {
        return when (error) {
            is DownloadTruncatedException -> {
                "通信が途中で切断されました (${DownloadByteFormat.format(error.totalRead)} / " +
                    "${DownloadByteFormat.format(error.expectedLength)})"
            }
            is UnknownHostException -> "サーバーに接続できませんでした"
            is SocketTimeoutException, is InterruptedIOException -> "通信がタイムアウトしました"
            else -> error.readableMessage() ?: UNKNOWN
        }
    }

    /**
     * 例外のメッセージを取り出す。
     * メッセージを持たない例外（NullPointerException 等）は原因が全く分からなくなるため、
     * 代わりに例外クラス名を返す。
     */
    private fun Throwable.readableMessage(): String? {
        message?.takeIf { it.isNotBlank() }?.let { return it }
        cause?.message?.takeIf { it.isNotBlank() }?.let { return it }
        return this::class.simpleName?.takeIf { it.isNotBlank() }
    }
}

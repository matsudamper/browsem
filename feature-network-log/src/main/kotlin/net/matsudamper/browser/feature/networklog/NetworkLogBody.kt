package net.matsudamper.browser.feature.networklog

/** プレビュー用に取得したレスポンス本文 */
sealed interface NetworkLogBody {
    data class Text(
        val text: String,
        val mimeType: String,
        val sizeBytes: Long,
    ) : NetworkLogBody

    class Binary(
        val bytes: ByteArray,
        val mimeType: String,
        val sizeBytes: Long,
    ) : NetworkLogBody

    data class Failure(
        val reason: Reason,
        /** サイズが分かっている場合のバイト数。不明な場合は -1 */
        val sizeBytes: Long = -1,
    ) : NetworkLogBody {
        enum class Reason {
            /** プレビュー上限を超えている */
            TooLarge,

            FetchFailed,

            /** GET 以外のため再取得できない */
            NotReplayable,

            /** 拡張機能へ問い合わせできなかった */
            Unavailable,
        }
    }
}

package net.matsudamper.browser.feature.networklog

/** プレビュー用に取得したレスポンス本文 */
sealed interface NetworkLogBody {
    /** テキストとして表示できる本文 */
    data class Text(
        val text: String,
        val mimeType: String,
        val sizeBytes: Long,
    ) : NetworkLogBody

    /** 画像等のバイナリ本文 */
    class Binary(
        val bytes: ByteArray,
        val mimeType: String,
        val sizeBytes: Long,
    ) : NetworkLogBody

    /** 取得できなかった場合 */
    data class Failure(
        val reason: Reason,
        /** サイズが分かっている場合のバイト数。不明な場合は -1 */
        val sizeBytes: Long = -1,
    ) : NetworkLogBody {
        enum class Reason {
            /** プレビュー上限を超えている */
            TooLarge,

            /** 再取得に失敗した */
            FetchFailed,

            /** GET 以外のため再取得できない */
            NotReplayable,

            /** 拡張機能へ問い合わせできなかった */
            Unavailable,
        }
    }
}

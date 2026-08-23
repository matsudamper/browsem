package net.matsudamper.browser.data

/**
 * バックアップのエクスポート/インポート処理の進捗。
 *
 * @param message 現在の処理内容を示す日本語テキスト
 * @param fraction 全体に対する進捗率 (0.0〜1.0)。算出できない区間では null
 */
data class BackupProgress(
    val message: String,
    val fraction: Float? = null,
)

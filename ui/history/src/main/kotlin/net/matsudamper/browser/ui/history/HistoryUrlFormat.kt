package net.matsudamper.browser.ui.history

/**
 * 閲覧履歴の URL 表示用フォーマット。
 * 単体テストしやすいよう Android 非依存の純粋関数として切り出している。
 */
object HistoryUrlFormat {
    private const val HTTPS_PREFIX = "https://"

    /**
     * 履歴一覧などに表示する URL 文字列を返す。
     * HTTPS の場合のみ `https://` を除き、ホスト名から表示する。
     */
    fun forDisplay(url: String): String {
        if (!url.startsWith(HTTPS_PREFIX, ignoreCase = true)) {
            return url
        }
        return url.substring(HTTPS_PREFIX.length)
    }
}

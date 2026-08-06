package net.matsudamper.browser.download

/** バイト数を人が読める単位（B/KB/MB）へ変換するユーティリティ。Android非依存でJVMテスト可能 */
object DownloadByteFormat {
    fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

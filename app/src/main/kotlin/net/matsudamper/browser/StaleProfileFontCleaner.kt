package net.matsudamper.browser

import android.content.Context
import java.io.File

private const val STALE_FONT_FILE_PREFIX = "browsem-"

/**
 * 日本語フォント対応の試験ビルドが Gecko プロファイルの fonts ディレクトリに置いたフォントを消す。
 * 残っていると Gecko がそれを使い続け、端末フォントで描けているかの検証結果を覆い隠す。
 */
internal object StaleProfileFontCleaner {
    fun delete(context: Context) {
        File(context.filesDir, "mozilla").listFiles()
            ?.filter { it.isDirectory }
            ?.map { profileDir -> File(profileDir, "fonts") }
            ?.forEach { fontsDir ->
                fontsDir.listFiles()
                    ?.filter { it.name.startsWith(STALE_FONT_FILE_PREFIX) }
                    ?.forEach { it.delete() }
                fontsDir.delete()
            }
    }
}

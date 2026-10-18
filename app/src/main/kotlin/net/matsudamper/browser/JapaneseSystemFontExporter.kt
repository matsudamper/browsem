package net.matsudamper.browser

import android.content.Context
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private const val TAG = "JapaneseSystemFontExporter"

/** Gecko のフォント設定から参照する、書き出した日本語フォントのファミリー名。 */
internal const val EXPORTED_JAPANESE_FONT_FAMILY = "Browsem Japanese Sans"

private const val EXPORTED_FONT_FILE_NAME = "browsem-japanese-sans.otf"

/**
 * Android の CJK フォント（NotoSansCJK の TTC）は、Gecko がファミリー名で探すと日本語フェイスを選べず
 * 中国語字形のフェイスになる。Android が日本語用と宣言しているフェイスを別名の単体フォントとして
 * Gecko プロファイルの fonts ディレクトリに書き出し、名前で確実に選べるようにする。
 *
 * Gecko は起動時に fonts ディレクトリを走査するため、GeckoRuntime 生成前に呼ぶ。
 * プロファイルが未作成の初回起動では書き出せず、次回起動から反映される。
 */
internal class JapaneseSystemFontExporter(
    private val context: Context,
) {
    fun export() {
        runCatching {
            val profileDir = findGeckoProfileDir() ?: return
            val source = findJapaneseSystemFont() ?: run {
                Log.w(TAG, "日本語のシステムフォントが見つからない")
                return
            }
            val sourceFile = checkNotNull(source.file)
            val outputFile = File(profileDir, "fonts/$EXPORTED_FONT_FILE_NAME")
            if (outputFile.exists() && outputFile.lastModified() >= sourceFile.lastModified()) return

            val extracted = RandomAccessFile(sourceFile, "r").use { file ->
                val buffer = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                SfntFaceExtractor.extract(buffer, source.ttcIndex, EXPORTED_JAPANESE_FONT_FAMILY)
            }
            outputFile.parentFile?.mkdirs()
            val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
            tempFile.writeBytes(extracted)
            check(tempFile.renameTo(outputFile)) { "書き出したフォントの配置に失敗" }
        }.onFailure { error ->
            Log.w(TAG, "日本語フォントの書き出しに失敗", error)
        }
    }

    private fun findJapaneseSystemFont(): Font? {
        return SystemFonts.getAvailableFonts()
            .filter { font ->
                val file = font.file ?: return@filter false
                val isJapanese = (0 until font.localeList.size()).any { font.localeList[it].language == "ja" }
                isJapanese &&
                    font.style.weight == FontStyle.FONT_WEIGHT_NORMAL &&
                    font.style.slant == FontStyle.FONT_SLANT_UPRIGHT &&
                    file.canRead()
            }
            // 明朝（Serif）や等幅（Mono）のフェイスも ja として宣言されているため除外する
            .sortedBy { font ->
                val name = font.file!!.name
                when {
                    name.contains("Serif", ignoreCase = true) || name.contains("Mono", ignoreCase = true) -> 2
                    name.contains("Sans", ignoreCase = true) -> 0
                    else -> 1
                }
            }
            .firstOrNull()
    }

    /** GeckoView のプロファイルは HOME（filesDir）/mozilla 配下に profiles.ini で管理される。 */
    private fun findGeckoProfileDir(): File? {
        val mozillaDir = File(context.filesDir, "mozilla")
        val profilesIni = File(mozillaDir, "profiles.ini")
        if (!profilesIni.exists()) return null
        val relativePath = profilesIni.readLines()
            .firstOrNull { it.startsWith("Path=") }
            ?.removePrefix("Path=")
            ?: return null
        val profileDir = if (relativePath.startsWith("/")) File(relativePath) else File(mozillaDir, relativePath)
        return profileDir.takeIf { it.isDirectory }
    }
}

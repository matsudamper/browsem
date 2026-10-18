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

private const val EXPORTED_FONT_FILE_PREFIX = "browsem-japanese-sans-"

// 書き出し形式を変えたら上げ、既存の書き出し済みファイルを作り直させる
private const val EXPORT_FORMAT_VERSION = 1

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
            val source = findJapaneseSystemFont()
            if (source == null) {
                Log.w(TAG, "日本語のシステムフォントが見つからない")
                return
            }
            val fontsDir = File(profileDir, "fonts")
            // バックアップ復元で別端末の書き出し結果が持ち込まれることがあるため、
            // mtime ではなく元フォントを識別する情報をファイル名に含めて作り直しを判定する
            val outputFile = File(fontsDir, "$EXPORTED_FONT_FILE_PREFIX${source.identity()}.otf")
            if (!outputFile.exists()) {
                writeExtractedFont(source, outputFile)
            }
            fontsDir.listFiles()
                ?.filter { it.name.startsWith(EXPORTED_FONT_FILE_PREFIX) && it != outputFile }
                ?.forEach { it.delete() }
        }.onFailure { error ->
            Log.w(TAG, "日本語フォントの書き出しに失敗", error)
        }
    }

    private fun writeExtractedFont(source: JapaneseFontSource, outputFile: File) {
        val extracted = RandomAccessFile(source.file, "r").use { file ->
            val buffer = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            SfntFaceExtractor.extract(buffer, source.ttcIndex, EXPORTED_JAPANESE_FONT_FAMILY)
        }
        outputFile.parentFile?.mkdirs()
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        tempFile.writeBytes(extracted)
        check(tempFile.renameTo(outputFile)) { "書き出したフォントの配置に失敗" }
    }

    private fun findJapaneseSystemFont(): JapaneseFontSource? {
        return SystemFonts.getAvailableFonts()
            .mapNotNull { font ->
                val file = font.file
                if (file == null || !file.canRead() || !font.isJapaneseRegular()) return@mapNotNull null
                JapaneseFontSource(file = file, ttcIndex = font.ttcIndex)
            }
            // 明朝（Serif）や等幅（Mono）のフェイスも ja として宣言されているため後回しにする
            .sortedBy { source ->
                val name = source.file.name
                when {
                    name.contains("Serif", ignoreCase = true) || name.contains("Mono", ignoreCase = true) -> 2
                    name.contains("Sans", ignoreCase = true) -> 0
                    else -> 1
                }
            }
            .firstOrNull()
    }

    private fun Font.isJapaneseRegular(): Boolean {
        val isJapanese = (0 until localeList.size()).any { localeList[it].language == "ja" }
        return isJapanese &&
            style.weight == FontStyle.FONT_WEIGHT_NORMAL &&
            style.slant == FontStyle.FONT_SLANT_UPRIGHT
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

    private class JapaneseFontSource(
        val file: File,
        val ttcIndex: Int,
    ) {
        fun identity(): String {
            val key = listOf(file.absolutePath, file.length(), file.lastModified(), ttcIndex, EXPORT_FORMAT_VERSION)
                .joinToString(":")
            return Integer.toHexString(key.hashCode())
        }
    }
}

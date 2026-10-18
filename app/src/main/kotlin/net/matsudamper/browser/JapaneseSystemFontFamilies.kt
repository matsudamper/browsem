package net.matsudamper.browser

import android.graphics.fonts.Font
import android.graphics.fonts.SystemFonts
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private const val TAG = "JapaneseSystemFontFamilies"

/**
 * Android が日本語（lang="ja"）に割り当てているシステムフォントのファミリー名。
 *
 * Chrome は Android のフォント表から言語に合うフォントを選ぶが、Gecko はファミリー名でしか
 * フォントを探せない。Android が日本語用としているフォントの名前を読み取って Gecko の候補リストに
 * 渡し、端末のフォント構成に関係なく Chrome と同じフォントで漢字を描けるようにする。
 */
internal class JapaneseSystemFontFamilies(
    val sansSerif: List<String>,
    val serif: List<String>,
    val monospace: List<String>,
) {
    companion object {
        val EMPTY = JapaneseSystemFontFamilies(sansSerif = listOf(), serif = listOf(), monospace = listOf())

        fun resolve(): JapaneseSystemFontFamilies {
            val faces = SystemFonts.getAvailableFonts()
                .filter { font -> font.isJapanese() }
                .mapNotNull { font -> font.file?.let { file -> FontFace(file = file, ttcIndex = font.ttcIndex) } }
                .distinct()
            if (faces.isEmpty()) {
                Log.w(TAG, "Android が日本語に割り当てたフォントが見つからない")
                return EMPTY
            }
            val namesByKind = faces.groupBy { it.kind() }
                .mapValues { (_, kindFaces) -> kindFaces.flatMap { it.readFamilyNames() }.distinct() }
            return JapaneseSystemFontFamilies(
                sansSerif = namesByKind[FaceKind.SANS_SERIF].orEmpty(),
                serif = namesByKind[FaceKind.SERIF].orEmpty(),
                monospace = namesByKind[FaceKind.MONOSPACE].orEmpty(),
            )
        }

        private fun Font.isJapanese(): Boolean {
            return (0 until localeList.size()).any { localeList[it].language == "ja" }
        }
    }

    private enum class FaceKind { SANS_SERIF, SERIF, MONOSPACE }

    private data class FontFace(
        val file: File,
        val ttcIndex: Int,
    ) {
        /** Android の Font API はセリフ・等幅の区別を公開しないため、ファイル名から判断する。 */
        fun kind(): FaceKind {
            return when {
                file.name.contains("Serif", ignoreCase = true) -> FaceKind.SERIF
                file.name.contains("Mono", ignoreCase = true) -> FaceKind.MONOSPACE
                else -> FaceKind.SANS_SERIF
            }
        }

        fun readFamilyNames(): List<String> {
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    SfntFamilyNameReader.readFamilyNames(buffer, ttcIndex)
                }
            }.getOrElse { error ->
                Log.w(TAG, "フォントのファミリー名の読み取りに失敗: $file#$ttcIndex", error)
                listOf()
            }
        }
    }
}

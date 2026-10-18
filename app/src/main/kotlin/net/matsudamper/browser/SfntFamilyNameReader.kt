package net.matsudamper.browser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

private const val TTC_TAG = 0x74746366 // "ttcf"
private const val NAME_TAG = 0x6E616D65 // "name"
private const val SFNT_HEADER_SIZE = 12
private const val TABLE_RECORD_SIZE = 16
private const val NAME_HEADER_SIZE = 6
private const val NAME_RECORD_SIZE = 12
private const val NAME_ID_FAMILY = 1
private const val NAME_ID_TYPOGRAPHIC_FAMILY = 16
private const val PLATFORM_UNICODE = 0
private const val PLATFORM_MACINTOSH = 1
private const val PLATFORM_WINDOWS = 3
private const val MACINTOSH_ENCODING_ROMAN = 0
private const val WINDOWS_ENCODING_SYMBOL = 0
private const val WINDOWS_ENCODING_UNICODE_BMP = 1
private const val WINDOWS_ENCODING_UNICODE_FULL = 10

/**
 * TrueType/OpenType フォント（TTC を含む）の name テーブルからファミリー名を読み取る。
 * Gecko はフォントをファミリー名で登録するため、同じ名前で候補リストに書けば一致する。
 */
internal object SfntFamilyNameReader {
    /**
     * Gecko と同じく typographic family（ID 16）を優先し、family（ID 1）を続ける。
     * Gecko がどの言語の名前で登録するかに依存しないよう、言語違いの名前もすべて返す。
     */
    fun readFamilyNames(font: ByteBuffer, faceIndex: Int): List<String> {
        val input = font.duplicate().order(ByteOrder.BIG_ENDIAN)
        val faceOffset = resolveFaceOffset(input, faceIndex)
        val numTables = input.getShort(faceOffset + 4).toInt() and 0xFFFF
        val nameTableOffset = (0 until numTables)
            .map { faceOffset + SFNT_HEADER_SIZE + it * TABLE_RECORD_SIZE }
            .firstOrNull { input.getInt(it) == NAME_TAG }
            ?.let { input.getInt(it + 8) }
            ?: return listOf()

        val count = input.getShort(nameTableOffset + 2).toInt() and 0xFFFF
        val stringStorage = nameTableOffset + (input.getShort(nameTableOffset + 4).toInt() and 0xFFFF)
        val names = (0 until count).mapNotNull { i ->
            val record = nameTableOffset + NAME_HEADER_SIZE + i * NAME_RECORD_SIZE
            val nameId = input.getShort(record + 6).toInt() and 0xFFFF
            if (nameId != NAME_ID_FAMILY && nameId != NAME_ID_TYPOGRAPHIC_FAMILY) return@mapNotNull null
            val platformId = input.getShort(record).toInt() and 0xFFFF
            val encodingId = input.getShort(record + 2).toInt() and 0xFFFF
            val charset = nameCharset(platformId, encodingId) ?: return@mapNotNull null
            val length = input.getShort(record + 8).toInt() and 0xFFFF
            val offset = input.getShort(record + 10).toInt() and 0xFFFF
            val text = String(input.readBytes(stringStorage + offset, length), charset)
            nameId to text.trim()
        }
        val typographicNames = names.filter { (nameId, _) -> nameId == NAME_ID_TYPOGRAPHIC_FAMILY }
        val familyNames = names.filter { (nameId, _) -> nameId == NAME_ID_FAMILY }
        return (typographicNames + familyNames)
            .map { (_, name) -> name }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Gecko（HarfBuzz）が名前として読むのは Unicode 系と Mac Roman のレコードだけなので、同じ範囲に限る。
     * Shift_JIS や Big5 を宣言する Mac のレコードを別の文字コードで復号すると、登録名と一致しない文字列になる。
     */
    private fun nameCharset(platformId: Int, encodingId: Int): Charset? {
        return when (platformId) {
            PLATFORM_UNICODE -> Charsets.UTF_16BE
            PLATFORM_WINDOWS -> when (encodingId) {
                WINDOWS_ENCODING_SYMBOL, WINDOWS_ENCODING_UNICODE_BMP, WINDOWS_ENCODING_UNICODE_FULL -> Charsets.UTF_16BE
                else -> null
            }

            PLATFORM_MACINTOSH -> if (encodingId == MACINTOSH_ENCODING_ROMAN) Charsets.ISO_8859_1 else null
            else -> null
        }
    }

    private fun resolveFaceOffset(input: ByteBuffer, faceIndex: Int): Int {
        if (input.getInt(0) != TTC_TAG) {
            require(faceIndex == 0) { "TTC ではないフォントにフェイス番号 $faceIndex は指定できない" }
            return 0
        }
        val numFonts = input.getInt(8)
        require(faceIndex in 0 until numFonts) { "フェイス番号 $faceIndex が範囲外（$numFonts 個）" }
        return input.getInt(12 + faceIndex * 4)
    }

    private fun ByteBuffer.readBytes(offset: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val reader = duplicate()
        reader.position(offset)
        reader.get(bytes)
        return bytes
    }
}

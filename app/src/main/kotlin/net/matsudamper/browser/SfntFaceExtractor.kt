package net.matsudamper.browser

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TTC_TAG = 0x74746366 // "ttcf"
private const val NAME_TAG = 0x6E616D65 // "name"
private const val SFNT_HEADER_SIZE = 12
private const val TABLE_RECORD_SIZE = 16
private const val NAME_RECORD_SIZE = 12
private const val NAME_HEADER_SIZE = 6
private const val PLATFORM_WINDOWS = 3
private const val ENCODING_UNICODE_BMP = 1
private const val LANGUAGE_EN_US = 0x409
private const val NAME_ID_FAMILY = 1
private const val NAME_ID_SUBFAMILY = 2
private const val NAME_ID_FULL = 4
private const val NAME_ID_POSTSCRIPT = 6

/**
 * name テーブル内でファミリー名として解釈される ID。
 * どれかに元の名前が残ると、フォント側が元ファミリーとして扱われてしまう。
 */
private val FAMILY_RELATED_NAME_IDS = setOf(1, 2, 3, 4, 6, 16, 17, 21, 22)

/**
 * TrueType/OpenType フォント（TTC を含む）から 1 フェイスを取り出し、
 * ファミリー名を差し替えた単体の SFNT として書き出す。
 */
internal object SfntFaceExtractor {
    fun extract(
        source: ByteBuffer,
        faceIndex: Int,
        familyName: String,
    ): ByteArray {
        val input = source.duplicate().order(ByteOrder.BIG_ENDIAN)
        val faceOffset = resolveFaceOffset(input, faceIndex)
        val sfntVersion = input.getInt(faceOffset)
        val numTables = input.getShort(faceOffset + 4).toInt() and 0xFFFF

        val tables = (0 until numTables).map { i ->
            val record = faceOffset + SFNT_HEADER_SIZE + i * TABLE_RECORD_SIZE
            val tag = input.getInt(record)
            val offset = input.getInt(record + 8)
            val length = input.getInt(record + 12)
            val data = if (tag == NAME_TAG) {
                buildNameTable(input, offset, familyName)
            } else {
                input.readBytes(offset, length)
            }
            tag to data
        }.sortedBy { (tag, _) -> tag.toUInt() }

        return writeSfnt(sfntVersion, tables)
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

    private fun buildNameTable(input: ByteBuffer, tableOffset: Int, familyName: String): ByteArray {
        val count = input.getShort(tableOffset + 2).toInt() and 0xFFFF
        val stringStorage = tableOffset + (input.getShort(tableOffset + 4).toInt() and 0xFFFF)

        val keptRecords = (0 until count).mapNotNull { i ->
            val record = tableOffset + NAME_HEADER_SIZE + i * NAME_RECORD_SIZE
            val nameId = input.getShort(record + 6).toInt() and 0xFFFF
            val languageId = input.getShort(record + 4).toInt() and 0xFFFF
            // 0x8000 以上は format 1 の言語タグ参照で、format 0 で書き出すと壊れる
            if (nameId in FAMILY_RELATED_NAME_IDS || languageId >= 0x8000) return@mapNotNull null
            val length = input.getShort(record + 8).toInt() and 0xFFFF
            val offset = input.getShort(record + 10).toInt() and 0xFFFF
            val bytes = input.readBytes(stringStorage + offset, length)
            NameRecord(
                platformId = input.getShort(record).toInt() and 0xFFFF,
                encodingId = input.getShort(record + 2).toInt() and 0xFFFF,
                languageId = languageId,
                nameId = nameId,
                bytes = bytes,
            )
        }
        val postScriptName = familyName.filter { it.isLetterOrDigit() } + "-Regular"
        val replacedRecords = listOf(
            NAME_ID_FAMILY to familyName,
            NAME_ID_SUBFAMILY to "Regular",
            NAME_ID_FULL to familyName,
            NAME_ID_POSTSCRIPT to postScriptName,
        ).map { (nameId, value) ->
            NameRecord(
                platformId = PLATFORM_WINDOWS,
                encodingId = ENCODING_UNICODE_BMP,
                languageId = LANGUAGE_EN_US,
                nameId = nameId,
                bytes = value.toByteArray(Charsets.UTF_16BE),
            )
        }
        // name レコードは platform/encoding/language/nameId の昇順が仕様上の要件
        val records = (keptRecords + replacedRecords).sortedWith(
            compareBy({ it.platformId }, { it.encodingId }, { it.languageId }, { it.nameId }),
        )

        val headerSize = NAME_HEADER_SIZE + records.size * NAME_RECORD_SIZE
        val output = ByteBuffer.allocate(headerSize + records.sumOf { it.bytes.size })
            .order(ByteOrder.BIG_ENDIAN)
        output.putShort(0)
        output.putShort(records.size.toShort())
        output.putShort(headerSize.toShort())
        var stringOffset = 0
        records.forEach { record ->
            output.putShort(record.platformId.toShort())
            output.putShort(record.encodingId.toShort())
            output.putShort(record.languageId.toShort())
            output.putShort(record.nameId.toShort())
            output.putShort(record.bytes.size.toShort())
            output.putShort(stringOffset.toShort())
            stringOffset += record.bytes.size
        }
        records.forEach { output.put(it.bytes) }
        return output.array()
    }

    private fun writeSfnt(sfntVersion: Int, tables: List<Pair<Int, ByteArray>>): ByteArray {
        val headerSize = SFNT_HEADER_SIZE + tables.size * TABLE_RECORD_SIZE
        val totalSize = headerSize + tables.sumOf { (_, data) -> padded(data.size) }
        val output = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        val entrySelector = Integer.highestOneBit(tables.size).countTrailingZeroBits()
        val searchRange = (1 shl entrySelector) * TABLE_RECORD_SIZE
        output.putInt(sfntVersion)
        output.putShort(tables.size.toShort())
        output.putShort(searchRange.toShort())
        output.putShort(entrySelector.toShort())
        output.putShort((tables.size * TABLE_RECORD_SIZE - searchRange).toShort())

        var dataOffset = headerSize
        tables.forEach { (tag, data) ->
            output.putInt(tag)
            output.putInt(checksum(data))
            output.putInt(dataOffset)
            output.putInt(data.size)
            dataOffset += padded(data.size)
        }
        tables.forEach { (_, data) ->
            output.put(data)
            repeat(padded(data.size) - data.size) { output.put(0) }
        }
        return output.array()
    }

    private fun ByteBuffer.readBytes(offset: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val reader = duplicate()
        reader.position(offset)
        reader.get(bytes)
        return bytes
    }

    private fun padded(size: Int): Int = (size + 3) and 3.inv()

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (i in data.indices) {
            sum += (data[i].toInt() and 0xFF) shl (24 - (i % 4) * 8)
        }
        return sum
    }

    private class NameRecord(
        val platformId: Int,
        val encodingId: Int,
        val languageId: Int,
        val nameId: Int,
        val bytes: ByteArray,
    )
}

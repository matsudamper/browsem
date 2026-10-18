package net.matsudamper.browser

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SfntFamilyNameReaderTest {

    @Test
    fun `TTC の指定フェイスから typographic family を優先してファミリー名を読む`() {
        val collection = buildCollection(
            listOf(
                nameTable(listOf(Name(nameId = 1, text = "Noto Sans CJK JP"))),
                nameTable(
                    listOf(
                        Name(nameId = 1, text = "MiSans Regular"),
                        Name(nameId = 16, text = "MiSans"),
                        Name(nameId = 16, text = "小米蘭亭", languageId = 0x804),
                        Name(nameId = 4, text = "MiSans Regular"),
                    ),
                ),
            ),
        )

        val names = SfntFamilyNameReader.readFamilyNames(ByteBuffer.wrap(collection), 1)

        assertEquals(listOf("MiSans", "小米蘭亭", "MiSans Regular"), names)
    }

    @Test
    fun `Unicode でも Mac Roman でもないレコードは読まない`() {
        val macJapanese = Name(nameId = 1, text = "ignored", platformId = 1, encodingId = 1)
        val macRoman = Name(nameId = 1, text = "Mac Roman", platformId = 1, encodingId = 0)
        val windowsShiftJis = Name(nameId = 1, text = "ignored too", platformId = 3, encodingId = 2)
        val single = buildSfnt(nameTable(listOf(macJapanese, macRoman, windowsShiftJis)), baseOffset = 0)

        val names = SfntFamilyNameReader.readFamilyNames(ByteBuffer.wrap(single), 0)

        assertEquals(listOf("Mac Roman"), names)
    }

    @Test
    fun `TTC でない単体フォントからも読める`() {
        val single = buildSfnt(nameTable(listOf(Name(nameId = 1, text = "Droid Sans Japanese"))), baseOffset = 0)

        val names = SfntFamilyNameReader.readFamilyNames(ByteBuffer.wrap(single), 0)

        assertEquals(listOf("Droid Sans Japanese"), names)
    }

    @Test
    fun `name テーブルが無ければ空`() {
        val single = buildSfnt(nameTable = null, baseOffset = 0)

        val names = SfntFamilyNameReader.readFamilyNames(ByteBuffer.wrap(single), 0)

        assertEquals(listOf<String>(), names)
    }

    private class Name(
        val nameId: Int,
        val text: String,
        val languageId: Int = 0x409,
        val platformId: Int = 3,
        val encodingId: Int = 1,
    ) {
        fun encode(): ByteArray {
            return if (platformId == 1) text.toByteArray(Charsets.ISO_8859_1) else text.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun nameTable(names: List<Name>): ByteArray {
        val encoded = names.map { it to it.encode() }
        val headerSize = 6 + encoded.size * 12
        val buffer = ByteBuffer.allocate(headerSize + encoded.sumOf { (_, bytes) -> bytes.size })
        buffer.putShort(0).putShort(encoded.size.toShort()).putShort(headerSize.toShort())
        var offset = 0
        encoded.forEach { (name, bytes) ->
            buffer.putShort(name.platformId.toShort()).putShort(name.encodingId.toShort())
            buffer.putShort(name.languageId.toShort()).putShort(name.nameId.toShort())
            buffer.putShort(bytes.size.toShort()).putShort(offset.toShort())
            offset += bytes.size
        }
        encoded.forEach { (_, bytes) -> buffer.put(bytes) }
        return buffer.array()
    }

    /** テーブルデータの位置は TTC 全体の先頭からのオフセットで書く必要がある。 */
    private fun buildSfnt(nameTable: ByteArray?, baseOffset: Int): ByteArray {
        val tables = listOfNotNull(GLYF_TAG to byteArrayOf(1, 2, 3, 4), nameTable?.let { NAME_TAG to it })
        val headerSize = 12 + tables.size * 16
        val buffer = ByteBuffer.allocate(headerSize + tables.sumOf { (_, data) -> data.size })
        buffer.putInt(0x00010000).putShort(tables.size.toShort()).putShort(0).putShort(0).putShort(0)
        var offset = baseOffset + headerSize
        tables.forEach { (tag, data) ->
            buffer.putInt(tag).putInt(0).putInt(offset).putInt(data.size)
            offset += data.size
        }
        tables.forEach { (_, data) -> buffer.put(data) }
        return buffer.array()
    }

    private fun buildCollection(nameTables: List<ByteArray>): ByteArray {
        val headerSize = 12 + nameTables.size * 4
        val sfnts = mutableListOf<ByteArray>()
        var offset = headerSize
        val offsets = nameTables.map { nameTable ->
            val current = offset
            val sfnt = buildSfnt(nameTable, current)
            sfnts += sfnt
            offset += sfnt.size
            current
        }
        val buffer = ByteBuffer.allocate(offset)
        buffer.putInt(0x74746366).putInt(0x00010000).putInt(nameTables.size)
        offsets.forEach { buffer.putInt(it) }
        sfnts.forEach { buffer.put(it) }
        return buffer.array()
    }

    private companion object {
        const val GLYF_TAG = 0x676C7966
        const val NAME_TAG = 0x6E616D65
    }
}

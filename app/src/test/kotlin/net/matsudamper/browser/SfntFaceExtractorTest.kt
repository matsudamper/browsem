package net.matsudamper.browser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SfntFaceExtractorTest {

    @Test
    fun `TTC から指定フェイスのテーブルを取り出しファミリー名を差し替える`() {
        val collection = buildCollection(
            listOf(
                face(glyphData = byteArrayOf(1, 1, 1), familyName = "Noto Sans CJK JP"),
                face(glyphData = byteArrayOf(2, 2, 2, 2, 2), familyName = "Noto Sans CJK SC"),
            ),
        )

        val extracted = SfntFaceExtractor.extract(ByteBuffer.wrap(collection), 1, "Browsem Test")

        val sfnt = ByteBuffer.wrap(extracted).order(ByteOrder.BIG_ENDIAN)
        assertArrayEquals(byteArrayOf(2, 2, 2, 2, 2), readTable(sfnt, GLYF_TAG))
        val names = readNames(readTable(sfnt, NAME_TAG))
        assertEquals("Browsem Test", names[1])
        assertEquals("Regular", names[2])
        assertEquals("Browsem Test", names[4])
        assertEquals("BrowsemTest-Regular", names[6])
        assertEquals("ファミリー名以外の name レコードは残す", "copyright", names[0])
    }

    @Test
    fun `TTC でない単体フォントもファミリー名を差し替えられる`() {
        val single = face(glyphData = byteArrayOf(3), familyName = "Original").let { buildSfnt(it, baseOffset = 0) }

        val extracted = SfntFaceExtractor.extract(ByteBuffer.wrap(single), 0, "Browsem Test")

        val names = readNames(readTable(ByteBuffer.wrap(extracted).order(ByteOrder.BIG_ENDIAN), NAME_TAG))
        assertEquals("Browsem Test", names[1])
    }

    private class Face(val tables: List<Pair<Int, ByteArray>>)

    private fun face(glyphData: ByteArray, familyName: String): Face {
        return Face(listOf(GLYF_TAG to glyphData, NAME_TAG to nameTable(mapOf(0 to "copyright", 1 to familyName))))
    }

    private fun nameTable(names: Map<Int, String>): ByteArray {
        val encoded = names.mapValues { it.value.toByteArray(Charsets.UTF_16BE) }
        val headerSize = 6 + encoded.size * 12
        val buffer = ByteBuffer.allocate(headerSize + encoded.values.sumOf { it.size })
        buffer.putShort(0).putShort(encoded.size.toShort()).putShort(headerSize.toShort())
        var offset = 0
        encoded.forEach { (nameId, bytes) ->
            buffer.putShort(3).putShort(1).putShort(0x409).putShort(nameId.toShort())
            buffer.putShort(bytes.size.toShort()).putShort(offset.toShort())
            offset += bytes.size
        }
        encoded.values.forEach { buffer.put(it) }
        return buffer.array()
    }

    /** テーブルデータの位置は TTC 全体の先頭からのオフセットで書く必要がある。 */
    private fun buildSfnt(face: Face, baseOffset: Int): ByteArray {
        val headerSize = 12 + face.tables.size * 16
        val buffer = ByteBuffer.allocate(headerSize + face.tables.sumOf { it.second.size })
        buffer.putInt(0x00010000).putShort(face.tables.size.toShort()).putShort(0).putShort(0).putShort(0)
        var offset = baseOffset + headerSize
        face.tables.forEach { (tag, data) ->
            buffer.putInt(tag).putInt(0).putInt(offset).putInt(data.size)
            offset += data.size
        }
        face.tables.forEach { buffer.put(it.second) }
        return buffer.array()
    }

    private fun buildCollection(faces: List<Face>): ByteArray {
        val headerSize = 12 + faces.size * 4
        val sfnts = mutableListOf<ByteArray>()
        var offset = headerSize
        val offsets = faces.map { face ->
            val current = offset
            val sfnt = buildSfnt(face, current)
            sfnts += sfnt
            offset += sfnt.size
            current
        }
        val buffer = ByteBuffer.allocate(offset)
        buffer.putInt(0x74746366).putInt(0x00010000).putInt(faces.size)
        offsets.forEach { buffer.putInt(it) }
        sfnts.forEach { buffer.put(it) }
        return buffer.array()
    }

    private fun readTable(sfnt: ByteBuffer, tag: Int): ByteArray {
        val numTables = sfnt.getShort(4).toInt()
        val record = (0 until numTables).map { 12 + it * 16 }.first { sfnt.getInt(it) == tag }
        return sfnt.readBytes(sfnt.getInt(record + 8), sfnt.getInt(record + 12))
    }

    private fun readNames(table: ByteArray): Map<Int, String> {
        val buffer = ByteBuffer.wrap(table)
        val storage = buffer.getShort(4).toInt()
        return (0 until buffer.getShort(2).toInt()).associate { i ->
            val record = 6 + i * 12
            val bytes = buffer.readBytes(storage + buffer.getShort(record + 10), buffer.getShort(record + 8).toInt())
            buffer.getShort(record + 6).toInt() to String(bytes, Charsets.UTF_16BE)
        }
    }

    private fun ByteBuffer.readBytes(offset: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val reader = duplicate()
        reader.position(offset)
        reader.get(bytes)
        return bytes
    }

    private companion object {
        const val GLYF_TAG = 0x676C7966
        const val NAME_TAG = 0x6E616D65
    }
}

package net.matsudamper.browser

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// android.graphics.Bitmap を生成するため Robolectric 上で実行する
@RunWith(RobolectricTestRunner::class)
class IcoDecoderTest {
    @Test
    fun isIcoData_pngBytes_returnsFalse() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        assertFalse(IcoDecoder.isIcoData(pngHeader))
    }

    @Test
    fun isIcoData_icoHeader_returnsTrue() {
        val ico = buildIco(listOf(bgra32Frame(width = 1, height = 1, colors = listOf(OPAQUE_RED))))

        assertTrue(IcoDecoder.isIcoData(ico))
    }

    @Test
    fun decodeLargestFrame_multipleFrames_選ぶのは最大解像度() {
        val ico = buildIco(
            listOf(
                bgra32Frame(width = 1, height = 1, colors = listOf(OPAQUE_RED)),
                bgra32Frame(width = 2, height = 2, colors = List(4) { OPAQUE_BLUE }),
            ),
        )

        val bitmap = IcoDecoder.decodeLargestFrame(ico)

        assertNotNull(bitmap)
        assertEquals(2, bitmap!!.width)
        assertEquals(2, bitmap.height)
    }

    @Test
    fun decodeLargestFrame_bgra32_ピクセルの色とアルファを保持する() {
        val colors = listOf(OPAQUE_RED, OPAQUE_BLUE, TRANSLUCENT_GREEN, OPAQUE_RED)
        val ico = buildIco(listOf(bgra32Frame(width = 2, height = 2, colors = colors)))

        val bitmap = IcoDecoder.decodeLargestFrame(ico)

        assertNotNull(bitmap)
        assertEquals(OPAQUE_RED, bitmap!!.getPixel(0, 0))
        assertEquals(OPAQUE_BLUE, bitmap.getPixel(1, 0))
        assertEquals(TRANSLUCENT_GREEN, bitmap.getPixel(0, 1))
    }

    @Test
    fun decodeLargestFrame_paletteWithAndMask_マスクされた画素は透明になる() {
        val ico = buildIco(
            listOf(
                indexed8Frame(
                    width = 2,
                    height = 2,
                    palette = listOf(OPAQUE_RED, OPAQUE_BLUE),
                    paletteIndices = listOf(0, 1, 1, 0),
                    transparentPixels = listOf(false, true, false, false),
                ),
            ),
        )

        val bitmap = IcoDecoder.decodeLargestFrame(ico)

        assertNotNull(bitmap)
        assertEquals(OPAQUE_RED, bitmap!!.getPixel(0, 0))
        assertEquals(0, bitmap.getPixel(1, 0))
        assertEquals(OPAQUE_BLUE, bitmap.getPixel(0, 1))
    }

    @Test
    fun decodeLargestFrame_frameDataOutOfRange_returnsNull() {
        val ico = buildIco(listOf(bgra32Frame(width = 1, height = 1, colors = listOf(OPAQUE_RED))))
        val broken = ico.copyOf()
        // ICONDIRENTRY のデータサイズをファイル長より大きく書き換える
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(ICON_DIR_SIZE + 8, Int.MAX_VALUE)

        assertNull(IcoDecoder.decodeLargestFrame(broken))
    }

    private fun bgra32Frame(width: Int, height: Int, colors: List<Int>): IcoFrameSource {
        val body = ByteArrayOutputStream()
        body.write(dibHeader(width = width, storedHeight = height * 2, bitCount = 32, paletteColorCount = 0))
        // DIB はボトムアップ格納
        for (row in height - 1 downTo 0) {
            for (column in 0 until width) {
                val color = colors[row * width + column]
                body.write(color and 0xFF)
                body.write((color shr 8) and 0xFF)
                body.write((color shr 16) and 0xFF)
                body.write((color ushr 24) and 0xFF)
            }
        }
        body.write(ByteArray(andMaskRowSize(width) * height))
        return IcoFrameSource(width = width, height = height, bitCount = 32, data = body.toByteArray())
    }

    private fun indexed8Frame(
        width: Int,
        height: Int,
        palette: List<Int>,
        paletteIndices: List<Int>,
        transparentPixels: List<Boolean>,
    ): IcoFrameSource {
        val body = ByteArrayOutputStream()
        body.write(
            dibHeader(
                width = width,
                storedHeight = height * 2,
                bitCount = 8,
                paletteColorCount = palette.size,
            ),
        )
        palette.forEach { color ->
            body.write(color and 0xFF)
            body.write((color shr 8) and 0xFF)
            body.write((color shr 16) and 0xFF)
            body.write(0)
        }
        val rowSize = ((width * 8 + 31) / 32) * 4
        for (row in height - 1 downTo 0) {
            val rowBytes = ByteArray(rowSize)
            for (column in 0 until width) {
                rowBytes[column] = paletteIndices[row * width + column].toByte()
            }
            body.write(rowBytes)
        }
        val maskRowSize = andMaskRowSize(width)
        for (row in height - 1 downTo 0) {
            val rowBytes = ByteArray(maskRowSize)
            for (column in 0 until width) {
                if (transparentPixels[row * width + column]) {
                    val index = column / 8
                    rowBytes[index] = (rowBytes[index].toInt() or (1 shl (7 - column % 8))).toByte()
                }
            }
            body.write(rowBytes)
        }
        return IcoFrameSource(width = width, height = height, bitCount = 8, data = body.toByteArray())
    }

    private fun dibHeader(width: Int, storedHeight: Int, bitCount: Int, paletteColorCount: Int): ByteArray {
        val header = ByteBuffer.allocate(DIB_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(DIB_HEADER_SIZE)
        header.putInt(width)
        header.putInt(storedHeight)
        header.putShort(1)
        header.putShort(bitCount.toShort())
        header.putInt(0)
        header.putInt(0)
        header.putInt(0)
        header.putInt(0)
        header.putInt(paletteColorCount)
        header.putInt(paletteColorCount)
        return header.array()
    }

    private fun andMaskRowSize(width: Int): Int = ((width + 31) / 32) * 4

    private fun buildIco(frames: List<IcoFrameSource>): ByteArray {
        val directorySize = ICON_DIR_SIZE + frames.size * ICON_DIR_ENTRY_SIZE
        val buffer = ByteBuffer
            .allocate(directorySize + frames.sumOf { it.data.size })
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0)
        buffer.putShort(1)
        buffer.putShort(frames.size.toShort())
        var dataOffset = directorySize
        frames.forEach { frame ->
            buffer.put(frame.width.toByte())
            buffer.put(frame.height.toByte())
            buffer.put(0)
            buffer.put(0)
            buffer.putShort(1)
            buffer.putShort(frame.bitCount.toShort())
            buffer.putInt(frame.data.size)
            buffer.putInt(dataOffset)
            dataOffset += frame.data.size
        }
        frames.forEach { frame -> buffer.put(frame.data) }
        return buffer.array()
    }

    private data class IcoFrameSource(
        val width: Int,
        val height: Int,
        val bitCount: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private companion object {
        const val ICON_DIR_SIZE = 6
        const val ICON_DIR_ENTRY_SIZE = 16
        const val DIB_HEADER_SIZE = 40
        const val OPAQUE_RED = 0xFFFF0000.toInt()
        const val OPAQUE_BLUE = 0xFF0000FF.toInt()
        const val TRANSLUCENT_GREEN = 0x8000FF00.toInt()
    }
}

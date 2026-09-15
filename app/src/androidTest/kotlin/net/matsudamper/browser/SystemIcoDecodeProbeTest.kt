package net.matsudamper.browser

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

// Android の BitmapFactory が ICO をどこまで扱えるかの実測用。調査が終わったら削除する。
@RunWith(AndroidJUnit4::class)
class SystemIcoDecodeProbeTest {
    @Test
    fun bitmapFactoryDecodesIcoLargestFrame() {
        val ico = buildIco(
            listOf(
                bgra32Frame(size = 16, color = 0xFFFF0000.toInt()),
                bgra32Frame(size = 64, color = 0xFF0000FF.toInt()),
            ),
        )

        val bitmap = BitmapFactory.decodeByteArray(ico, 0, ico.size)

        assertNotNull("BitmapFactory は ICO をデコードできなかった", bitmap)
        val decoded = checkNotNull(bitmap)
        assertEquals("デコードされたフレームの幅", 64, decoded.width)
        assertEquals("デコードされたフレームの高さ", 64, decoded.height)
    }

    private fun bgra32Frame(size: Int, color: Int): IcoFrameSource {
        val body = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(40)
        header.putInt(size)
        header.putInt(size * 2)
        header.putShort(1)
        header.putShort(32)
        repeat(6) { header.putInt(0) }
        body.write(header.array())
        repeat(size * size) {
            body.write(color and 0xFF)
            body.write((color shr 8) and 0xFF)
            body.write((color shr 16) and 0xFF)
            body.write((color ushr 24) and 0xFF)
        }
        body.write(ByteArray(((size + 31) / 32) * 4 * size))
        return IcoFrameSource(size = size, data = body.toByteArray())
    }

    private fun buildIco(frames: List<IcoFrameSource>): ByteArray {
        val directorySize = 6 + frames.size * 16
        val buffer = ByteBuffer
            .allocate(directorySize + frames.sumOf { it.data.size })
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0)
        buffer.putShort(1)
        buffer.putShort(frames.size.toShort())
        var dataOffset = directorySize
        frames.forEach { frame ->
            buffer.put(frame.size.toByte())
            buffer.put(frame.size.toByte())
            buffer.put(0)
            buffer.put(0)
            buffer.putShort(1)
            buffer.putShort(32)
            buffer.putInt(frame.data.size)
            buffer.putInt(dataOffset)
            dataOffset += frame.data.size
        }
        frames.forEach { frame -> buffer.put(frame.data) }
        return buffer.array()
    }

    private class IcoFrameSource(val size: Int, val data: ByteArray)
}

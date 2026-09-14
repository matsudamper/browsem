package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ICO (favicon.ico) から最も高品質なフレームを取り出してデコードする。
 *
 * Android の BitmapFactory は ICO コンテナを扱えないため、複数解像度を内包する favicon.ico は
 * そのままでは丸ごとデコード失敗になる。ここでコンテナを解析して最大解像度のフレームを選び、
 * PNG 埋め込みなら BitmapFactory へ、BMP(DIB) 形式なら自前でピクセル展開する。
 */
internal object IcoDecoder {
    private const val ICON_DIR_SIZE = 6
    private const val ICON_DIR_ENTRY_SIZE = 16
    private const val ICON_TYPE = 1

    // ICONDIRENTRY の幅・高さは 1 バイトのため 256 を 0 で表す
    private const val IMPLICIT_MAX_DIMENSION = 256
    private const val DIB_HEADER_MIN_SIZE = 40
    private const val BI_RGB = 0
    private const val PALETTE_ENTRY_SIZE = 4
    private const val OPAQUE_ALPHA = 0xFF

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isIcoData(bytes: ByteArray): Boolean {
        if (bytes.size < ICON_DIR_SIZE) return false
        val buffer = bytes.littleEndianBuffer()
        return buffer.getShort(0).toInt() == 0 && buffer.getShort(2).toInt() == ICON_TYPE
    }

    /**
     * 面積が最大、同一面積ならビット深度が大きいフレームを優先してデコードする。
     * 先頭のフレームが未対応形式でも次点のフレームへ順に切り替える。
     */
    fun decodeLargestFrame(bytes: ByteArray): Bitmap? {
        if (!isIcoData(bytes)) return null
        val buffer = bytes.littleEndianBuffer()
        val frameCount = buffer.getShort(4).toInt() and 0xFFFF
        if (frameCount <= 0) return null
        return (0 until frameCount)
            .mapNotNull { index -> parseFrame(buffer, bytes.size, index) }
            .sortedWith(
                compareByDescending<IcoFrame> { it.width * it.height }
                    .thenByDescending { it.bitCount },
            )
            .firstNotNullOfOrNull { frame -> decodeFrame(bytes, frame) }
    }

    private fun parseFrame(buffer: ByteBuffer, totalBytes: Int, index: Int): IcoFrame? {
        val entryOffset = ICON_DIR_SIZE + index * ICON_DIR_ENTRY_SIZE
        if (entryOffset + ICON_DIR_ENTRY_SIZE > totalBytes) return null
        val dataLength = buffer.getInt(entryOffset + 8)
        val dataOffset = buffer.getInt(entryOffset + 12)
        if (dataLength <= 0 || dataOffset < 0) return null
        if (dataOffset.toLong() + dataLength.toLong() > totalBytes.toLong()) return null
        return IcoFrame(
            width = buffer.get(entryOffset).toDimension(),
            height = buffer.get(entryOffset + 1).toDimension(),
            bitCount = buffer.getShort(entryOffset + 6).toInt() and 0xFFFF,
            dataOffset = dataOffset,
            dataLength = dataLength,
        )
    }

    private fun decodeFrame(bytes: ByteArray, frame: IcoFrame): Bitmap? {
        return if (bytes.startsWithPngSignature(frame.dataOffset)) {
            runCatching { decodeEmbeddedPng(bytes, frame) }.getOrNull()
        } else {
            runCatching { decodeDib(bytes, frame) }.getOrNull()
        }
    }

    /**
     * ICO のフレームサイズは 256 が上限のため、それを超える PNG が埋め込まれていても
     * 縮小してからデコードし、メモリ使用量を抑える。
     */
    private fun decodeEmbeddedPng(bytes: ByteArray, frame: IcoFrame): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, frame.dataOffset, frame.dataLength, boundsOptions)
        val maxDimension = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        if (maxDimension <= 0) return null
        var sampleSize = 1
        while (maxDimension / sampleSize > IMPLICIT_MAX_DIMENSION) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, frame.dataOffset, frame.dataLength, decodeOptions)
    }

    private fun decodeDib(bytes: ByteArray, frame: IcoFrame): Bitmap? {
        val buffer = bytes.littleEndianBuffer()
        val header = parseDibHeader(buffer, frame) ?: return null
        val pixelOffset = frame.dataOffset + header.headerSize + header.paletteColorCount * PALETTE_ENTRY_SIZE
        val palette = readPalette(buffer, frame.dataOffset + header.headerSize, header.paletteColorCount)
        val rowSize = rowSizeOf(header.width, header.bitCount)
        val maskOffset = pixelOffset + rowSize * header.height
        val frameEnd = frame.dataOffset + frame.dataLength
        if (maskOffset > frameEnd) return null

        val pixels = IntArray(header.width * header.height)
        var hasTranslucentPixel = false
        for (row in 0 until header.height) {
            // DIB はボトムアップ格納なので最終行から読み出す
            val rowOffset = pixelOffset + (header.height - 1 - row) * rowSize
            for (column in 0 until header.width) {
                val color = readPixel(buffer, rowOffset, column, header.bitCount, palette) ?: return null
                if (color ushr 24 != OPAQUE_ALPHA) hasTranslucentPixel = true
                pixels[row * header.width + column] = color
            }
        }
        // 32bpp でもアルファを全て 0 で埋めた ICO が実在するため、その場合は AND マスクへ委ねる
        val needsAndMask = header.bitCount != 32 || !hasTranslucentPixel
        if (needsAndMask && header.hasAndMask) {
            applyAndMask(buffer, maskOffset, frameEnd, header, pixels)
        }
        return Bitmap.createBitmap(pixels, header.width, header.height, Bitmap.Config.ARGB_8888)
    }

    private fun parseDibHeader(buffer: ByteBuffer, frame: IcoFrame): DibHeader? {
        val offset = frame.dataOffset
        if (frame.dataLength < DIB_HEADER_MIN_SIZE) return null
        val headerSize = buffer.getInt(offset)
        if (headerSize < DIB_HEADER_MIN_SIZE) return null
        val compression = buffer.getInt(offset + 16)
        if (compression != BI_RGB) return null
        val width = buffer.getInt(offset + 4)
        val storedHeight = buffer.getInt(offset + 8)
        val bitCount = buffer.getShort(offset + 14).toInt() and 0xFFFF
        if (width <= 0 || storedHeight <= 0) return null
        if (bitCount !in supportedBitCounts) return null
        // 高さは XOR 画像と AND マスクを縦に連結した値で格納されるのが通例だが、
        // マスクを持たず実高さをそのまま書く ICO もあるため ICONDIRENTRY の高さと突き合わせる
        val hasAndMask = storedHeight != frame.height
        val height = if (hasAndMask) storedHeight / 2 else storedHeight
        if (height <= 0) return null
        val colorsUsed = buffer.getInt(offset + 32)
        val paletteColorCount = when {
            bitCount > 8 -> 0
            colorsUsed > 0 -> colorsUsed
            else -> 1 shl bitCount
        }
        val pixelOffset = offset + headerSize + paletteColorCount * PALETTE_ENTRY_SIZE
        if (pixelOffset + rowSizeOf(width, bitCount) * height > offset + frame.dataLength) return null
        return DibHeader(
            headerSize = headerSize,
            width = width,
            height = height,
            bitCount = bitCount,
            paletteColorCount = paletteColorCount,
            hasAndMask = hasAndMask,
        )
    }

    private fun readPalette(buffer: ByteBuffer, offset: Int, colorCount: Int): IntArray {
        return IntArray(colorCount) { index ->
            val entryOffset = offset + index * PALETTE_ENTRY_SIZE
            argbOf(
                alpha = OPAQUE_ALPHA,
                red = buffer.get(entryOffset + 2).toUnsignedInt(),
                green = buffer.get(entryOffset + 1).toUnsignedInt(),
                blue = buffer.get(entryOffset).toUnsignedInt(),
            )
        }
    }

    private fun readPixel(
        buffer: ByteBuffer,
        rowOffset: Int,
        column: Int,
        bitCount: Int,
        palette: IntArray,
    ): Int? {
        return when (bitCount) {
            32 -> {
                val offset = rowOffset + column * 4
                argbOf(
                    alpha = buffer.get(offset + 3).toUnsignedInt(),
                    red = buffer.get(offset + 2).toUnsignedInt(),
                    green = buffer.get(offset + 1).toUnsignedInt(),
                    blue = buffer.get(offset).toUnsignedInt(),
                )
            }

            24 -> {
                val offset = rowOffset + column * 3
                argbOf(
                    alpha = OPAQUE_ALPHA,
                    red = buffer.get(offset + 2).toUnsignedInt(),
                    green = buffer.get(offset + 1).toUnsignedInt(),
                    blue = buffer.get(offset).toUnsignedInt(),
                )
            }

            16 -> {
                // RGB555。各 5bit を 8bit へ引き伸ばす
                val raw = buffer.getShort(rowOffset + column * 2).toInt() and 0xFFFF
                argbOf(
                    alpha = OPAQUE_ALPHA,
                    red = expand5BitChannel(raw shr 10),
                    green = expand5BitChannel(raw shr 5),
                    blue = expand5BitChannel(raw),
                )
            }

            else -> readPaletteIndexedPixel(buffer, rowOffset, column, bitCount, palette)
        }
    }

    private fun readPaletteIndexedPixel(
        buffer: ByteBuffer,
        rowOffset: Int,
        column: Int,
        bitCount: Int,
        palette: IntArray,
    ): Int? {
        val pixelsPerByte = 8 / bitCount
        val byteValue = buffer.get(rowOffset + column / pixelsPerByte).toUnsignedInt()
        val shift = (pixelsPerByte - 1 - column % pixelsPerByte) * bitCount
        val paletteIndex = (byteValue shr shift) and ((1 shl bitCount) - 1)
        return palette.getOrNull(paletteIndex)
    }

    private fun applyAndMask(
        buffer: ByteBuffer,
        maskOffset: Int,
        frameEnd: Int,
        header: DibHeader,
        pixels: IntArray,
    ) {
        val maskRowSize = rowSizeOf(header.width, bitCount = 1)
        if (maskOffset + maskRowSize * header.height > frameEnd) return
        for (row in 0 until header.height) {
            val rowOffset = maskOffset + (header.height - 1 - row) * maskRowSize
            for (column in 0 until header.width) {
                val byteValue = buffer.get(rowOffset + column / 8).toUnsignedInt()
                val isTransparent = (byteValue shr (7 - column % 8)) and 1 == 1
                val index = row * header.width + column
                pixels[index] = if (isTransparent) {
                    0
                } else {
                    pixels[index] or (OPAQUE_ALPHA shl 24)
                }
            }
        }
    }

    private fun rowSizeOf(width: Int, bitCount: Int): Int {
        // DIB の 1 行は 4 バイト境界へパディングされる
        return ((width * bitCount + 31) / 32) * 4
    }

    private fun expand5BitChannel(rawChannel: Int): Int {
        val value = rawChannel and 0x1F
        return (value shl 3) or (value shr 2)
    }

    private fun argbOf(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun ByteArray.littleEndianBuffer(): ByteBuffer {
        return ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ByteArray.startsWithPngSignature(offset: Int): Boolean {
        if (offset + pngSignature.size > size) return false
        return pngSignature.indices.all { index -> this[offset + index] == pngSignature[index] }
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

    private fun Byte.toDimension(): Int {
        val value = toUnsignedInt()
        return if (value == 0) IMPLICIT_MAX_DIMENSION else value
    }

    private val supportedBitCounts = setOf(1, 2, 4, 8, 16, 24, 32)

    private data class IcoFrame(
        val width: Int,
        val height: Int,
        val bitCount: Int,
        val dataOffset: Int,
        val dataLength: Int,
    )

    private data class DibHeader(
        val headerSize: Int,
        val width: Int,
        val height: Int,
        val bitCount: Int,
        val paletteColorCount: Int,
        val hasAndMask: Boolean,
    )
}

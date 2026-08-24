package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import com.caverock.androidsvg.SVG

/**
 * SVG を Bitmap へ描画する。
 * BitmapFactory は SVG を扱えないため、プレビューとサムネイルではこちらを使う。
 */
internal object NetworkLogSvgRenderer {
    private const val TAG = "NetworkLogSvg"

    // 縦横比が取れない SVG を描画するときの基準サイズ
    private const val FALLBACK_SIDE = 1f

    /**
     * [source] の SVG を、長辺が [maxPixels] になる大きさで描画する。
     * 解釈できない場合は null を返す。
     */
    fun render(source: String, maxPixels: Int): Bitmap? {
        val svg = runCatching { SVG.getFromString(source) }.getOrElse { error ->
            Log.d(TAG, "SVG の解釈に失敗", error)
            return null
        }
        val (width, height) = svg.documentSize()
        val scale = maxPixels / maxOf(width, height)
        val pixelWidth = (width * scale).toInt().coerceAtLeast(1)
        val pixelHeight = (height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(
                canvas,
                RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()),
            )
            bitmap
        }.getOrElse { error ->
            Log.d(TAG, "SVG の描画に失敗", error)
            null
        }
    }

    /** MIME タイプが SVG かどうか */
    fun isSvg(mimeType: String): Boolean {
        return mimeType.substringBefore(';').trim().equals("image/svg+xml", ignoreCase = true)
    }

    /**
     * 描画に使う縦横比を求める。
     * width/height 属性が無い SVG では viewBox を使い、それも無ければ正方形として扱う。
     */
    private fun SVG.documentSize(): Pair<Float, Float> {
        val width = documentWidth
        val height = documentHeight
        if (width > 0f && height > 0f) return width to height
        val viewBox = documentViewBox
        if (viewBox != null && viewBox.width() > 0f && viewBox.height() > 0f) {
            return viewBox.width() to viewBox.height()
        }
        return FALLBACK_SIDE to FALLBACK_SIDE
    }
}

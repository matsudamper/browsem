package net.matsudamper.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.drawable.IconCompat

/**
 * ホーム画面ピン留めおよび Recents 表示で使用するランチャーアイコンを生成する。
 *
 * apple-touch-icon のような全面塗りのアイコンを `IconCompat.createWithBitmap` でそのまま
 * 渡すと、ランチャーによっては「アプリ」スタイルのピン (FLAG_ACTIVITY_NEW_DOCUMENT 等を
 * 含むピン) を作成した際にアイコンが反映されず、ターゲット Activity の icon
 * (= アプリのデフォルトアイコン) にフォールバックする現象が確認されている。
 *
 * 対策として、ここでは元画像を adaptive icon の安全領域 (108dp 中 66dp) に内接
 * させ、外側を透明で埋めた adaptive bitmap を生成する。これにより、ランチャーがどの
 * マスク形状（円、squircle 等）を適用しても元画像の内容が欠けず、また adaptive icon
 * として正しく認識されてアイコン差し替えが行われる。
 */
internal object LauncherIconFactory {
    // adaptive icon の "セーフゾーン" 内側に元画像を配置するための比率。
    // adaptive icon 仕様では 108dp のうち中央 66dp が確実に表示される領域なので、
    // この領域を超えないよう 66/108 (≒ 0.611) を用いる。これより大きくすると
    // ランチャーのマスク (円・squircle 等) で端が欠ける可能性がある。
    private const val SAFE_ZONE_RATIO = 66f / 108f

    // 出力アイコンのピクセルサイズ。ランチャー側でさらにスケールされる前提のため、
    // HomeScreenIconFetcher の MAX_SHORTCUT_ICON_SIZE と同じ 192px を使う。
    private const val CANVAS_SIZE = 192

    fun toAdaptiveIconCompat(bitmap: Bitmap): IconCompat {
        val canvasSize = CANVAS_SIZE
        val innerSize = (canvasSize * SAFE_ZONE_RATIO).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // 非正方形の画像でも歪まないよう、アスペクト比を保ったまま safe zone に内接する
        // 最大サイズを求め、中央に配置する (レターボックス)。
        val srcWidth = bitmap.width.coerceAtLeast(1)
        val srcHeight = bitmap.height.coerceAtLeast(1)
        val scale = innerSize.toFloat() / maxOf(srcWidth, srcHeight)
        val drawWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
        val left = (canvasSize - drawWidth) / 2
        val top = (canvasSize - drawHeight) / 2
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = Rect(left, top, left + drawWidth, top + drawHeight)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        return IconCompat.createWithAdaptiveBitmap(output)
    }
}

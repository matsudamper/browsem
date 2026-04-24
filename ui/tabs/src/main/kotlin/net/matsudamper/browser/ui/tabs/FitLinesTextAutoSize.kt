package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 最大フォントサイズで BasicText の maxLines に収まる場合はそのサイズを返し、
 * 収まらずに visual overflow (ellipsis) が発生する場合は [overflowExtraReduction] 分だけ縮小する
 * [TextAutoSize] 実装。
 *
 * BasicText 側の maxLines は [TextAutoSizeLayoutScope.performLayout] 内部で適用されるため、
 * `lineCount` は常に maxLines 以下にクランプされる。このため収まり判定には
 * [androidx.compose.ui.text.TextLayoutResult.hasVisualOverflow] を用いる。
 *
 * 縮小後のサイズが [minFontSize] を下回る場合は [minFontSize] でクランプする。
 *
 * @param minFontSize 縮小後に下回らせない最小フォントサイズ。
 * @param maxFontSize overflow しない場合に使用する、通常時のフォントサイズ。
 * @param overflowExtraReduction overflow 発生時に [maxFontSize] から引く縮小量。
 *   0 の場合は overflow しても [maxFontSize] のまま (ellipsis で表示)。
 */
internal data class FitLinesTextAutoSize(
    val minFontSize: TextUnit,
    val maxFontSize: TextUnit,
    val overflowExtraReduction: TextUnit = 0.sp,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        val maxLayout = performLayout(constraints, text, maxFontSize)
        if (!maxLayout.hasVisualOverflow) return maxFontSize

        val reduced = (maxFontSize.value - overflowExtraReduction.value).sp
        return if (reduced.value < minFontSize.value) minFontSize else reduced
    }
}

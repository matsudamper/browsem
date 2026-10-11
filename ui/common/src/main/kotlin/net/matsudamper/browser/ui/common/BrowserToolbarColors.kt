package net.matsudamper.browser.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

data class BrowserToolbarColors(
    val resolvedToolbarColor: Color,
    val urlBarBackgroundColor: Color,
    val urlBarContentColor: Color,
    val colorSource: String,
    // ツールバー背景が明るい場合 true（ステータスバーアイコンを黒にする用途）
    val isBrightBackground: Boolean,
)

fun resolveBrowserToolbarColors(
    toolbarColor: Color?,
    defaultToolbarColor: Color,
): BrowserToolbarColors {
    val resolvedToolbarColor = toolbarColor ?: defaultToolbarColor
    val isBrightThemeColor = resolvedToolbarColor.isBright()
    val urlBarBackgroundColor = if (isBrightThemeColor) {
        Color.Black
    } else {
        Color.LightGray
    }
    val urlBarContentColor = if (urlBarBackgroundColor.isBright()) {
        Color.Black
    } else {
        Color.White
    }

    return BrowserToolbarColors(
        resolvedToolbarColor = resolvedToolbarColor,
        urlBarBackgroundColor = urlBarBackgroundColor,
        urlBarContentColor = urlBarContentColor,
        colorSource = if (toolbarColor == null) "default" else "theme",
        isBrightBackground = isBrightThemeColor,
    )
}

private fun Color.isBright(): Boolean = luminance() >= 0.5f

fun Color.toArgbHex(): String {
    val raw = toArgb().toUInt().toString(16).padStart(8, '0')
    return "#${raw.uppercase()}"
}

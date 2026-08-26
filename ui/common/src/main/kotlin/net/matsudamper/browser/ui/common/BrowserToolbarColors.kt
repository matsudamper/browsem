package net.matsudamper.browser.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

data class BrowserToolbarColors(
    val resolvedToolbarColor: Color,
    val urlBarBackgroundColor: Color,
    val toolbarContentColor: Color,
    val colorSource: String,
    // ツールバー背景が明るい場合 true（ステータスバーアイコンを黒にする用途）
    val isBrightBackground: Boolean,
)

fun resolveBrowserToolbarColors(
    toolbarColor: Color?,
    defaultToolbarColor: Color,
    isAppDarkTheme: Boolean,
): BrowserToolbarColors {
    val resolvedToolbarColor = toolbarColor ?: defaultToolbarColor
    val isBrightThemeColor = toolbarColor?.luminance()?.let { it >= 0.5f } ?: !isAppDarkTheme
    // ステータスバー領域は resolvedToolbarColor で塗られるため、実際の背景色の輝度で判定する
    val isBrightBackground = resolvedToolbarColor.isBrightBackground()
    val urlBarBackgroundColor = if (isBrightThemeColor) {
        Color.Black
    } else {
        Color.LightGray
    }
    val toolbarContentColor = if (isBrightThemeColor) {
        Color.White
    } else {
        Color.Black
    }

    return BrowserToolbarColors(
        resolvedToolbarColor = resolvedToolbarColor,
        urlBarBackgroundColor = urlBarBackgroundColor,
        toolbarContentColor = toolbarContentColor,
        colorSource = if (toolbarColor == null) "default" else "theme",
        isBrightBackground = isBrightBackground,
    )
}

fun Color.toArgbHex(): String {
    val raw = toArgb().toUInt().toString(16).padStart(8, '0')
    return "#${raw.uppercase()}"
}

package net.matsudamper.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

internal data class BrowserToolbarColors(
    val resolvedToolbarColor: Color,
    val urlBarBackgroundColor: Color,
    val toolbarContentColor: Color,
    val colorSource: String,
)

internal fun resolveBrowserToolbarColors(
    toolbarColor: Color?,
    defaultToolbarColor: Color,
    isSystemDarkTheme: Boolean,
): BrowserToolbarColors {
    val resolvedToolbarColor = toolbarColor ?: defaultToolbarColor
    val isBrightThemeColor = toolbarColor?.luminance()?.let { it >= 0.5f } ?: !isSystemDarkTheme
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
    )
}

internal fun Color.toArgbHex(): String {
    val raw = toArgb().toUInt().toString(16).padStart(8, '0')
    return "#${raw.uppercase()}"
}

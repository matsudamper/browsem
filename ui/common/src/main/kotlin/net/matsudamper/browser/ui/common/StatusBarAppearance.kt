package net.matsudamper.browser.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun StatusBarAppearanceEffect(backgroundColor: Color) {
    val isBrightBackground = backgroundColor.luminance() >= 0.5f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isBrightBackground
        }
    }
}

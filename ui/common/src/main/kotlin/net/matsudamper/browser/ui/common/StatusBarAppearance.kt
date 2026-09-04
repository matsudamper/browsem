package net.matsudamper.browser.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private const val BRIGHT_BACKGROUND_LUMINANCE_THRESHOLD = 0.5f

fun Color.isBrightBackground(): Boolean = luminance() >= BRIGHT_BACKGROUND_LUMINANCE_THRESHOLD

@Composable
fun isAppInDarkTheme(): Boolean = !MaterialTheme.colorScheme.background.isBrightBackground()

@Composable
fun StatusBarAppearanceEffect(isBrightBackground: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isBrightBackground
        }
    }
}

@Composable
fun ThemeSurfaceStatusBarAppearanceEffect() {
    StatusBarAppearanceEffect(
        isBrightBackground = MaterialTheme.colorScheme.surface.isBrightBackground(),
    )
}

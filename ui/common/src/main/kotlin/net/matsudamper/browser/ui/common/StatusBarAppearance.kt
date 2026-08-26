package net.matsudamper.browser.ui.common

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
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
            val window = findWindow(view) ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isBrightBackground
        }
    }
}

private fun findWindow(view: android.view.View): android.view.Window? {
    (view.parent as? DialogWindowProvider)?.window?.let { return it }
    var context = view.context
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context.window
        }
        context = context.baseContext
    }
    return null
}

@Composable
fun ThemeSurfaceStatusBarAppearanceEffect() {
    StatusBarAppearanceEffect(
        isBrightBackground = MaterialTheme.colorScheme.surface.isBrightBackground(),
    )
}

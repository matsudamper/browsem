package net.matsudamper.browser.ui.common

import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
fun StatusBarAppearanceEffect(backgroundColor: Color) {
    val isBrightBackground = backgroundColor.luminance() >= 0.5f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.findWindow() ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isBrightBackground
        }
    }
}

private fun View.findWindow(): Window? {
    (parent as? DialogWindowProvider)?.window?.let { return it }
    return findActivity()?.window
}

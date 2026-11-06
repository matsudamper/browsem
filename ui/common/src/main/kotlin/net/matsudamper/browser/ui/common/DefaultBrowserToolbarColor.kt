package net.matsudamper.browser.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val LightDefaultToolbarColor = Color(0xFFF1F1F1)
private val DarkDefaultToolbarColor = Color(0xFF262626)

@Composable
@ReadOnlyComposable
fun defaultBrowserToolbarColor(): Color {
    val isDarkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDarkScheme) DarkDefaultToolbarColor else LightDefaultToolbarColor
}

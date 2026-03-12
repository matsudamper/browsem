package net.matsudamper.browser

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserToolbarColorsTest {

    @Test
    fun providedBrightThemeColorUsesDarkUrlBarBackground() {
        val palette = resolveBrowserToolbarColors(
            toolbarColor = Color(0xFFFFFFFF),
            defaultToolbarColor = Color(0xFF123456),
            isSystemDarkTheme = true,
        )

        assertEquals(Color(0xFFFFFFFF), palette.resolvedToolbarColor)
        assertEquals(Color.Black, palette.urlBarBackgroundColor)
        assertEquals(Color.White, palette.toolbarContentColor)
        assertEquals("theme", palette.colorSource)
    }

    @Test
    fun providedDarkThemeColorUsesLightUrlBarBackground() {
        val palette = resolveBrowserToolbarColors(
            toolbarColor = Color(0xFF111111),
            defaultToolbarColor = Color(0xFF123456),
            isSystemDarkTheme = false,
        )

        assertEquals(Color.LightGray, palette.urlBarBackgroundColor)
        assertEquals(Color.Black, palette.toolbarContentColor)
    }

    @Test
    fun defaultThemeUsesSystemThemeFallback() {
        val lightPalette = resolveBrowserToolbarColors(
            toolbarColor = null,
            defaultToolbarColor = Color(0xFF123456),
            isSystemDarkTheme = false,
        )
        val darkPalette = resolveBrowserToolbarColors(
            toolbarColor = null,
            defaultToolbarColor = Color(0xFF123456),
            isSystemDarkTheme = true,
        )

        assertEquals("default", lightPalette.colorSource)
        assertEquals(Color.Black, lightPalette.urlBarBackgroundColor)
        assertEquals(Color.LightGray, darkPalette.urlBarBackgroundColor)
    }
}

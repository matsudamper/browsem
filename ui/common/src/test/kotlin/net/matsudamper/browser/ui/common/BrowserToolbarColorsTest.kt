package net.matsudamper.browser.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserToolbarColorsTest {

    @Test
    fun providedBrightThemeColorUsesDarkUrlBarBackground() {
        val palette = resolveBrowserToolbarColors(
            toolbarColor = Color(0xFFFFFFFF),
            defaultToolbarColor = Color(0xFF123456),
        )

        assertEquals(Color(0xFFFFFFFF), palette.resolvedToolbarColor)
        assertEquals(Color.Black, palette.urlBarBackgroundColor)
        assertEquals(Color.White, palette.urlBarContentColor)
        assertEquals("theme", palette.colorSource)
    }

    @Test
    fun providedDarkThemeColorUsesLightUrlBarBackground() {
        val palette = resolveBrowserToolbarColors(
            toolbarColor = Color(0xFF111111),
            defaultToolbarColor = Color(0xFF123456),
        )

        assertEquals(Color.LightGray, palette.urlBarBackgroundColor)
        assertEquals(Color.Black, palette.urlBarContentColor)
    }

    @Test
    fun defaultToolbarColorDecidesUrlBarColors() {
        val lightPalette = resolveBrowserToolbarColors(
            toolbarColor = null,
            defaultToolbarColor = Color(0xFFEADDFF),
        )
        val darkPalette = resolveBrowserToolbarColors(
            toolbarColor = null,
            defaultToolbarColor = Color(0xFF4F378B),
        )

        assertEquals("default", lightPalette.colorSource)
        assertEquals(Color.Black, lightPalette.urlBarBackgroundColor)
        assertEquals(Color.White, lightPalette.urlBarContentColor)
        assertEquals(Color.LightGray, darkPalette.urlBarBackgroundColor)
        assertEquals(Color.Black, darkPalette.urlBarContentColor)
    }
}

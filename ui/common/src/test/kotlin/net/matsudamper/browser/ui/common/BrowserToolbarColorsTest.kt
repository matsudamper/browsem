package net.matsudamper.browser.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(Color.White, palette.toolbarContentColor)
        assertEquals("theme", palette.colorSource)
        assertTrue(palette.isBrightBackground)
    }

    @Test
    fun providedDarkThemeColorUsesLightUrlBarBackground() {
        val palette = resolveBrowserToolbarColors(
            toolbarColor = Color(0xFF111111),
            defaultToolbarColor = Color(0xFF123456),
        )

        assertEquals(Color.LightGray, palette.urlBarBackgroundColor)
        assertEquals(Color.Black, palette.toolbarContentColor)
        assertFalse(palette.isBrightBackground)
    }

    @Test
    fun defaultThemeUsesDefaultToolbarColor() {
        val lightPalette = resolveBrowserToolbarColors(
            toolbarColor = null,
            defaultToolbarColor = Color(0xFFEADDFF),
        )
        val darkPalette = resolveBrowserToolbarColors(
            toolbarColor = null,
            defaultToolbarColor = Color(0xFF111111),
        )

        assertEquals("default", lightPalette.colorSource)
        assertEquals(Color.Black, lightPalette.urlBarBackgroundColor)
        assertEquals(Color.LightGray, darkPalette.urlBarBackgroundColor)
        assertTrue(lightPalette.isBrightBackground)
        assertFalse(darkPalette.isBrightBackground)
    }
}

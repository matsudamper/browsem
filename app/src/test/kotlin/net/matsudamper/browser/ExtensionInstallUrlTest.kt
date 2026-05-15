package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionInstallUrlTest {

    @Test
    fun androidAddonPageUsesAndroidDownloadEndpoint() {
        assertEquals(
            "https://addons.mozilla.org/android/downloads/latest/adguard-adblocker/latest.xpi",
            resolveAmoInstallUriFromPage("https://addons.mozilla.org/ja/android/addon/adguard-adblocker/")
        )
    }

    @Test
    fun firefoxAddonPageUsesFirefoxDownloadEndpoint() {
        assertEquals(
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi",
            resolveAmoInstallUriFromPage("https://addons.mozilla.org/ja/firefox/addon/ublock-origin/")
        )
    }

    @Test
    fun directXpiUrlIsReturnedAsIs() {
        assertEquals(
            "https://addons.mozilla.org/firefox/downloads/file/123/example.xpi",
            resolveAmoInstallUriFromPage("https://addons.mozilla.org/firefox/downloads/file/123/example.xpi")
        )
    }

    @Test
    fun nonAmoUrlReturnsNull() {
        assertNull(resolveAmoInstallUriFromPage("https://example.com/addon/adguard-adblocker/"))
    }
}

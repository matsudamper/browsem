package net.matsudamper.browser.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryUrlFormatTest {
    @Test
    fun httpsUrlIsDisplayedFromHost() {
        assertEquals("example.com/path", HistoryUrlFormat.forDisplay("https://example.com/path"))
        assertEquals("example.com", HistoryUrlFormat.forDisplay("https://example.com"))
    }

    @Test
    fun httpsPrefixIsCaseInsensitive() {
        assertEquals("example.com", HistoryUrlFormat.forDisplay("HTTPS://example.com"))
    }

    @Test
    fun nonHttpsUrlIsNotModified() {
        assertEquals("http://example.com/path", HistoryUrlFormat.forDisplay("http://example.com/path"))
        assertEquals("file:///tmp/page.html", HistoryUrlFormat.forDisplay("file:///tmp/page.html"))
    }
}

package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebShareTextTest {
    @Test
    fun buildWebShareBody_joinsTextAndUriWithNewline() {
        assertEquals(
            "Example text\nhttps://example.com",
            buildWebShareBody("Example text", "https://example.com"),
        )
    }

    @Test
    fun buildWebShareBody_returnsTextOnlyWhenUriMissing() {
        assertEquals("Example text", buildWebShareBody("Example text", null))
    }

    @Test
    fun buildWebShareBody_returnsUriOnlyWhenTextMissing() {
        assertEquals("https://example.com", buildWebShareBody(null, "https://example.com"))
    }

    @Test
    fun buildWebShareBody_returnsEmptyWhenBothMissing() {
        assertEquals("", buildWebShareBody(null, null))
    }

    @Test
    fun buildWebShareBody_preservesLeadingAndTrailingWhitespace() {
        assertEquals("  indented text\n", buildWebShareBody("  indented text\n", null))
    }

    @Test
    fun hasWebShareContent_returnsTrueWhenWhitespaceOnlyFieldsAreExcluded() {
        assertTrue(hasWebShareContent("  title  ", null, null))
        assertFalse(hasWebShareContent("   ", null, null))
    }
}

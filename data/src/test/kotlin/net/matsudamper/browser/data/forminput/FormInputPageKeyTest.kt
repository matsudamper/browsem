package net.matsudamper.browser.data.forminput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormInputPageKeyTest {
    @Test
    fun parseRootPath() {
        val key = parseFormInputPageKey("https://example.com/")
        assertEquals(FormInputPageKey(host = "example.com", path = ""), key)
    }

    @Test
    fun parsePathWithoutTrailingSlash() {
        val key = parseFormInputPageKey("https://example.com/foo/bar/")
        assertEquals(FormInputPageKey(host = "example.com", path = "/foo/bar"), key)
    }

    @Test
    fun hashIsIgnored() {
        val key = parseFormInputPageKey("https://example.com/foo#section")
        assertEquals(FormInputPageKey(host = "example.com", path = "/foo"), key)
    }

    @Test
    fun differentPathsAreDistinct() {
        val first = parseFormInputPageKey("https://example.com/foo")
        val second = parseFormInputPageKey("https://example.com/bar")
        assertEquals(FormInputPageKey(host = "example.com", path = "/foo"), first)
        assertEquals(FormInputPageKey(host = "example.com", path = "/bar"), second)
    }

    @Test
    fun aboutBlankReturnsNull() {
        assertNull(parseFormInputPageKey("about:blank"))
    }
}

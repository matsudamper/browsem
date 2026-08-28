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
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 443, path = ""),
            key,
        )
    }

    @Test
    fun parsePathWithoutTrailingSlash() {
        val key = parseFormInputPageKey("https://example.com/foo/bar/")
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 443, path = "/foo/bar"),
            key,
        )
    }

    @Test
    fun parseHttpUsesDefaultPort() {
        val key = parseFormInputPageKey("http://example.com/form")
        assertEquals(
            FormInputPageKey(scheme = "http", host = "example.com", port = 80, path = "/form"),
            key,
        )
    }

    @Test
    fun parseExplicitPort() {
        val key = parseFormInputPageKey("https://example.com:8443/form")
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 8443, path = "/form"),
            key,
        )
    }

    @Test
    fun hashIsIgnored() {
        val key = parseFormInputPageKey("https://example.com/foo#section")
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 443, path = "/foo"),
            key,
        )
    }

    @Test
    fun differentPathsAreDistinct() {
        val first = parseFormInputPageKey("https://example.com/foo")
        val second = parseFormInputPageKey("https://example.com/bar")
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 443, path = "/foo"),
            first,
        )
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 443, path = "/bar"),
            second,
        )
    }

    @Test
    fun differentOriginsAreDistinct() {
        val https = parseFormInputPageKey("https://example.com/form")
        val http = parseFormInputPageKey("http://example.com/form")
        val customPort = parseFormInputPageKey("https://example.com:8443/form")
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 443, path = "/form"),
            https,
        )
        assertEquals(
            FormInputPageKey(scheme = "http", host = "example.com", port = 80, path = "/form"),
            http,
        )
        assertEquals(
            FormInputPageKey(scheme = "https", host = "example.com", port = 8443, path = "/form"),
            customPort,
        )
    }

    @Test
    fun aboutBlankReturnsNull() {
        assertNull(parseFormInputPageKey("about:blank"))
    }

    @Test
    fun displayFormInputOriginOmitsDefaultPort() {
        assertEquals(
            "https://example.com",
            displayFormInputOrigin(FormInputOrigin(scheme = "https", host = "example.com", port = 443)),
        )
        assertEquals(
            "http://example.com:8080",
            displayFormInputOrigin(FormInputOrigin(scheme = "http", host = "example.com", port = 8080)),
        )
    }
}

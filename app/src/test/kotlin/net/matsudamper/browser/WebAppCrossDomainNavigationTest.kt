package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppCrossDomainNavigationTest {
    @Test
    fun sameHostIsNotCrossDomain() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "https://example.com/other-path",
                pinnedHost = "example.com",
            ),
        )
    }

    @Test
    fun differentHostIsCrossDomain() {
        assertTrue(
            isWebAppCrossDomainNavigation(
                url = "https://other.example.org/page",
                pinnedHost = "example.com",
            ),
        )
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "https://Example.COM/page",
                pinnedHost = "example.com",
            ),
        )
    }

    @Test
    fun nullPinnedHostIsNotCrossDomain() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "https://example.com/page",
                pinnedHost = null,
            ),
        )
    }

    @Test
    fun urlWithoutHostIsNotCrossDomain() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "about:blank",
                pinnedHost = "example.com",
            ),
        )
    }
}

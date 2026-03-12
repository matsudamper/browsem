package net.matsudamper.browser.screen.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScreenViewModelPolicyTest {

    @Test
    fun webSuggestionsAreDisabledForBlankInput() {
        assertFalse(shouldFetchWebSuggestions(""))
    }

    @Test
    fun webSuggestionsAreDisabledForHttpUrl() {
        assertFalse(shouldFetchWebSuggestions("https://example.com"))
    }

    @Test
    fun webSuggestionsAreDisabledForAnySchemeUrl() {
        assertFalse(shouldFetchWebSuggestions("about:blank"))
        assertFalse(shouldFetchWebSuggestions("file:///storage/emulated/0/Download/test.html"))
        assertFalse(shouldFetchWebSuggestions("HTTPS://example.com"))
    }

    @Test
    fun webSuggestionsAreDisabledForHostLikeInput() {
        assertFalse(shouldFetchWebSuggestions("example.com"))
    }

    @Test
    fun webSuggestionsAreEnabledForKeywordInput() {
        assertTrue(shouldFetchWebSuggestions("kotlin compose browser"))
    }
}

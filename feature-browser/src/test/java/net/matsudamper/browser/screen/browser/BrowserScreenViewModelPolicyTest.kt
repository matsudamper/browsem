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
    fun webSuggestionsAreDisabledForHostLikeInput() {
        assertFalse(shouldFetchWebSuggestions("example.com"))
    }

    @Test
    fun webSuggestionsAreEnabledForKeywordInput() {
        assertTrue(shouldFetchWebSuggestions("kotlin compose browser"))
    }
}

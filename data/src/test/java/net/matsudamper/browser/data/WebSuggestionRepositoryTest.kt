package net.matsudamper.browser.data

import net.matsudamper.browser.data.websuggestion.buildSuggestionRequest
import net.matsudamper.browser.data.websuggestion.parseSuggestionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSuggestionRepositoryTest {

    @Test
    fun googleRequestUsesFirefoxCompatibleEndpoint() {
        val request = buildSuggestionRequest(
            searchProvider = SearchProvider.GOOGLE,
            query = "kotlin coroutines",
        )

        assertEquals(
            "https://www.google.com/complete/search?client=firefox&q=kotlin+coroutines",
            request?.url,
        )
    }

    @Test
    fun customProviderDoesNotBuildRequest() {
        assertNull(
            buildSuggestionRequest(
                searchProvider = SearchProvider.CUSTOM,
                query = "anything",
            ),
        )
    }

    @Test
    fun googleResponseParsesSecondArrayAndDeduplicates() {
        val suggestions = parseSuggestionResponse(
            searchProvider = SearchProvider.GOOGLE,
            body = """["kotlin",["kotlin coroutine"," kotlin coroutine ","kotlin flow"]]""",
        )

        assertEquals(
            listOf("kotlin coroutine", "kotlin flow"),
            suggestions,
        )
    }

    @Test
    fun duckDuckGoResponseParsesPhraseField() {
        val suggestions = parseSuggestionResponse(
            searchProvider = SearchProvider.DUCKDUCKGO,
            body = """
                [
                  {"phrase":"duck duck go browser"},
                  {"phrase":" duck duck go search "}
                ]
            """.trimIndent(),
        )

        assertEquals(
            listOf("duck duck go browser", "duck duck go search"),
            suggestions,
        )
    }

    @Test
    fun webSuggestionsDefaultToEnabledWhenUnset() {
        assertTrue(BrowserSettings.getDefaultInstance().resolvedEnableWebSuggestions())
    }

    @Test
    fun explicitFalseDisablesWebSuggestions() {
        val settings = BrowserSettings.newBuilder()
            .setEnableWebSuggestions(false)
            .build()

        assertEquals(false, settings.resolvedEnableWebSuggestions())
    }
}

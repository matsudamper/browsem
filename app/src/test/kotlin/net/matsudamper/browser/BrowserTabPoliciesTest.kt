package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabPoliciesTest {

    @Test
    fun themeColorIsMatchedIgnoringFragmentAndTrailingSlash() {
        assertTrue(
            isThemeColorForCurrentPage(
                currentPageUrl = "https://example.com/path/#section",
                reportedUrl = "https://example.com/path",
            )
        )
    }

    @Test
    fun blankReportedUrlDoesNotMatchThemeColor() {
        assertFalse(
            isThemeColorForCurrentPage(
                currentPageUrl = "https://example.com/path",
                reportedUrl = "",
            )
        )
    }

    @Test
    fun toolbarColorIsNotResetForFragmentOnlyNavigation() {
        assertFalse(
            shouldResetToolbarColor(
                fromUrl = "https://example.com/path#old",
                toUrl = "https://example.com/path#new",
            )
        )
    }

    @Test
    fun toolbarColorIsResetForDifferentPage() {
        assertTrue(
            shouldResetToolbarColor(
                fromUrl = "https://example.com/path",
                toUrl = "https://example.com/other",
            )
        )
    }

    @Test
    fun historySuggestionsAreShownWhenFocusedAndCurrentUrlExists() {
        assertTrue(
            shouldShowUrlSuggestions(
                showFindInPage = false,
                isUrlInputFocused = true,
                suggestionCount = 0,
                currentPageUrl = "https://example.com",
            )
        )
    }

    @Test
    fun suggestionsAreShownWhenOnlySuggestionItemsExist() {
        assertTrue(
            shouldShowUrlSuggestions(
                showFindInPage = false,
                isUrlInputFocused = true,
                suggestionCount = 2,
                currentPageUrl = "",
            )
        )
    }

    @Test
    fun suggestionsAreHiddenWhileFindInPageIsOpen() {
        assertFalse(
            shouldShowUrlSuggestions(
                showFindInPage = true,
                isUrlInputFocused = true,
                suggestionCount = 3,
                currentPageUrl = "https://example.com",
            )
        )
    }
}

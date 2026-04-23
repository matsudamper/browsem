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

    // --- shouldResetTranslationOnLocationChange ---

    /**
     * 翻訳済みページから別ページへ遷移すると翻訳状態がリセットされることを確認するリグレッション防止テスト。
     * 「翻訳した後にページ遷移すると翻訳バーが消えるが、翻訳されている状態になっている」バグ修正の確認。
     */
    @Test
    fun translationStateIsResetWhenNavigatingToDifferentPage() {
        assertTrue(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
            )
        )
    }

    @Test
    fun translationStateIsNotResetWhenNavigatingBackToOriginalPage() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-a",
                originalPageUrlForRevert = "https://example.com/page-a",
            )
        )
    }

    @Test
    fun translationStateIsNotResetWhenAlreadyIdle() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Idle,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
            )
        )
    }

    @Test
    fun translationStateIsNotResetForDataUrl() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "data:text/html,<html></html>",
                originalPageUrlForRevert = "https://example.com/page-a",
            )
        )
    }

    @Test
    fun translationLoadingStateIsResetOnNavigation() {
        assertTrue(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Loading,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
            )
        )
    }

    @Test
    fun translationStateIsResetWhenOriginalPageUrlIsNull() {
        assertTrue(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = null,
            )
        )
    }
}

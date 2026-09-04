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
            ),
        )
    }

    @Test
    fun blankReportedUrlDoesNotMatchThemeColor() {
        assertFalse(
            isThemeColorForCurrentPage(
                currentPageUrl = "https://example.com/path",
                reportedUrl = "",
            ),
        )
    }

    @Test
    fun toolbarColorIsNotResetForFragmentOnlyNavigation() {
        assertFalse(
            shouldResetToolbarColor(
                fromUrl = "https://example.com/path#old",
                toUrl = "https://example.com/path#new",
            ),
        )
    }

    @Test
    fun toolbarColorIsResetForDifferentPage() {
        assertTrue(
            shouldResetToolbarColor(
                fromUrl = "https://example.com/path",
                toUrl = "https://example.com/other",
            ),
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
            ),
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
            ),
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
            ),
        )
    }

    // --- shouldResetTranslationOnLocationChange ---

    /**
     * 翻訳済みページからフルページロードで別ページへ遷移すると翻訳状態がリセットされることを確認するリグレッション防止テスト。
     */
    @Test
    fun translationStateIsResetWhenNavigatingToDifferentPage() {
        assertTrue(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun translationStateIsNotResetOnSpaNavigation() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
                isFullPageLoad = false,
            ),
        )
    }

    @Test
    fun translationStateIsNotResetWhenNavigatingBackToOriginalPage() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-a",
                originalPageUrlForRevert = "https://example.com/page-a",
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun translationStateIsNotResetWhenAlreadyIdle() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Idle,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun translationStateIsNotResetForDataUrl() {
        assertFalse(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "data:text/html,<html></html>",
                originalPageUrlForRevert = "https://example.com/page-a",
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun translationLoadingStateIsResetOnNavigation() {
        assertTrue(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Loading,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = "https://example.com/page-a",
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun translationStateIsResetWhenOriginalPageUrlIsNull() {
        assertTrue(
            shouldResetTranslationOnLocationChange(
                translationState = TranslationState.Translated,
                url = "https://example.com/page-b",
                originalPageUrlForRevert = null,
                isFullPageLoad = true,
            ),
        )
    }

    @Test
    fun externalDownloadInitialUrlMatchesResponseIgnoringFragmentAndTrailingSlash() {
        assertTrue(
            matchesExternalDownloadInitialUrl(
                initialUrl = "https://example.com/file.zip/",
                responseUri = "https://example.com/file.zip#download",
            ),
        )
    }

    @Test
    fun externalDownloadInitialUrlDoesNotMatchDifferentResponse() {
        assertFalse(
            matchesExternalDownloadInitialUrl(
                initialUrl = "https://example.com/page",
                responseUri = "https://example.com/file.zip",
            ),
        )
    }

    @Test
    fun webAppSameHostIsNotCrossDomain() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "https://example.com/other-path",
                pinnedHost = "example.com",
            ),
        )
    }

    @Test
    fun webAppDifferentHostIsCrossDomain() {
        assertTrue(
            isWebAppCrossDomainNavigation(
                url = "https://other.example.org/page",
                pinnedHost = "example.com",
            ),
        )
    }

    @Test
    fun webAppHostComparisonIsCaseInsensitive() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "https://Example.COM/page",
                pinnedHost = "example.com",
            ),
        )
    }

    @Test
    fun webAppNullPinnedHostIsNotCrossDomain() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "https://example.com/page",
                pinnedHost = null,
            ),
        )
    }

    @Test
    fun webAppUrlWithoutHostIsNotCrossDomain() {
        assertFalse(
            isWebAppCrossDomainNavigation(
                url = "about:blank",
                pinnedHost = "example.com",
            ),
        )
    }
}

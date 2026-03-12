package net.matsudamper.browser

internal fun isThemeColorForCurrentPage(currentPageUrl: String, reportedUrl: String): Boolean {
    if (reportedUrl.isBlank()) return false
    return normalizedBrowserPageKey(currentPageUrl) == normalizedBrowserPageKey(reportedUrl)
}

internal fun shouldResetToolbarColor(fromUrl: String, toUrl: String): Boolean {
    return normalizedBrowserPageKey(fromUrl) != normalizedBrowserPageKey(toUrl)
}

internal fun shouldShowHistorySuggestions(
    showFindInPage: Boolean,
    isUrlInputFocused: Boolean,
    suggestionCount: Int,
    currentPageUrl: String,
): Boolean {
    return !showFindInPage &&
        isUrlInputFocused &&
        (suggestionCount > 0 || currentPageUrl.isNotBlank())
}

internal fun normalizedBrowserPageKey(url: String): String {
    return url
        .substringBefore("#")
        .removeSuffix("/")
}

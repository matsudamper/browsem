package net.matsudamper.browser

/**
 * ページ遷移時に翻訳状態をリセットすべきかを判定する。
 *
 * SPA 遷移（pushState / 同一ドキュメント内 history 移動）ではリセットしない。
 * data: URL（翻訳コンテンツ）や翻訳元URLへの遷移もリセットしない。
 *
 * @param isFullPageLoad フルページロード（onPageStart が先行した場合）か否か。
 *   SPA 遷移では false になるため翻訳は継続される。
 */
internal fun shouldResetTranslationOnLocationChange(
    translationState: TranslationState,
    url: String,
    originalPageUrlForRevert: String?,
    isFullPageLoad: Boolean,
): Boolean {
    return isFullPageLoad &&
        translationState != TranslationState.Idle &&
        !url.startsWith("data:") &&
        url != originalPageUrlForRevert
}

/**
 * ページ遷移時に翻訳キャッシュを捨てるべきかを判定する。
 *
 * 翻訳バーが Idle（原文表示中）でも別ページの訳文を持ち越さないよう、
 * 翻訳状態とは独立にフルページロードで捨てる。data: URL（翻訳コンテンツ）は
 * 同じページの表示替えなので残す。
 */
internal fun shouldClearTranslationCacheOnLocationChange(url: String, isFullPageLoad: Boolean): Boolean {
    return isFullPageLoad && !url.startsWith("data:")
}

internal fun isThemeColorForCurrentPage(currentPageUrl: String, reportedUrl: String): Boolean {
    if (reportedUrl.isBlank()) return false
    return normalizedBrowserPageKey(currentPageUrl) == normalizedBrowserPageKey(reportedUrl)
}

internal fun shouldResetToolbarColor(fromUrl: String, toUrl: String): Boolean {
    return normalizedBrowserPageKey(fromUrl) != normalizedBrowserPageKey(toUrl)
}

internal fun shouldShowUrlSuggestions(
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

/** 外部 Intent の初期 URL に対応するダウンロード応答かどうかを判定する */
internal fun matchesExternalDownloadInitialUrl(initialUrl: String, responseUri: String): Boolean {
    return normalizedBrowserPageKey(initialUrl) == normalizedBrowserPageKey(responseUri)
}

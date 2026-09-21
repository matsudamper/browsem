package net.matsudamper.browser

import net.matsudamper.browser.core.TabSummary
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.ProfileId

internal fun BrowserTab.toPersistedTabState(): PersistedTabState = PersistedTabState(
    url = currentUrl,
    sessionState = sessionState,
    title = title,
    tabId = tabId,
    openerTabId = openerTabId.orEmpty(),
    themeColor = themeColor,
    pageZoomPercent = pageZoomPercent,
    profileId = ProfileId.fromGeckoContextId(session.settings.contextId),
)

internal fun BrowserTab.toSummary(): TabSummary = TabSummary(
    id = tabId,
    title = title,
    url = currentUrl,
    openerTabId = openerTabId,
    previewBitmapArray = previewBitmap,
    themeColor = themeColor,
)

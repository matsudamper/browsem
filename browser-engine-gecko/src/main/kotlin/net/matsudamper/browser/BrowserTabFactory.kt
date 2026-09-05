package net.matsudamper.browser

import net.matsudamper.browser.data.PersistedTabState
import org.mozilla.geckoview.GeckoSession

internal class BrowserTabFactory(
    private val persistenceCoordinator: BrowserTabPersistenceCoordinator,
    private val onTabStateChanged: () -> Unit,
) {
    fun createTab(
        tabId: String,
        session: GeckoSession,
        initialUrl: String,
        sessionState: String,
        title: String,
        previewBitmapArray: ByteArray?,
        themeColor: Int? = null,
        pageZoomPercent: Int = DEFAULT_PAGE_ZOOM_PERCENT,
        openerTabId: String? = null,
    ): BrowserTab {
        return BrowserTab(
            tabId = tabId,
            session = session,
            openerTabId = openerTabId,
            currentUrl = initialUrl,
            sessionState = sessionState,
            title = title.ifBlank { initialUrl },
            previewBitmap = previewBitmapArray ?: byteArrayOf(),
            themeColor = themeColor,
            pageZoomPercent = pageZoomPercent,
            onStateChanged = onTabStateChanged,
            onUrlChanged = persistenceCoordinator::persistUrl,
            onSessionStateChanged = persistenceCoordinator::persistSessionState,
            onTitleChanged = persistenceCoordinator::persistTitle,
            onPreviewBitmapChanged = persistenceCoordinator::persistPreviewBitmap,
            onThemeColorChanged = persistenceCoordinator::persistThemeColor,
            onPageZoomPercentChanged = persistenceCoordinator::persistPageZoomPercent,
        ).also { tab ->
            tab.bindSessionDelegates()
        }
    }

    fun createDetachedTab(
        persistedTabState: PersistedTabState,
        previewBitmapArray: ByteArray?,
    ): BrowserTab {
        return createTab(
            tabId = persistedTabState.tabId,
            session = GeckoSession(),
            initialUrl = persistedTabState.url,
            sessionState = persistedTabState.sessionState,
            title = persistedTabState.title.ifBlank { persistedTabState.url },
            previewBitmapArray = previewBitmapArray,
            themeColor = persistedTabState.themeColor,
            pageZoomPercent = persistedTabState.pageZoomPercent,
            openerTabId = persistedTabState.openerTabId.ifBlank { null },
        ).also { tab ->
            tab.pendingSessionState = persistedTabState.sessionState.takeIf { it.isNotBlank() }
        }
    }
}

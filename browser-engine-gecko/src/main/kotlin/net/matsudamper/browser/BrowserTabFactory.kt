package net.matsudamper.browser

import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.ProfileId
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

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
            session = createSessionForProfile(persistedTabState.profileId),
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

    companion object {
        /**
         * プロファイルごとに Cookie やサイトデータを分けた GeckoSession を作る。
         * contextId は open 後に変更できないため、セッション生成時にプロファイルを確定させる。
         */
        fun createSessionForProfile(profileId: ProfileId): GeckoSession {
            val contextId = profileId.geckoContextId
            return if (contextId == null) {
                GeckoSession()
            } else {
                GeckoSession(GeckoSessionSettings.Builder().contextId(contextId).build())
            }
        }
    }
}

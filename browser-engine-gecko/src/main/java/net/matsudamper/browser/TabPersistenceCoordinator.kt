package net.matsudamper.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.TabRepository

class TabPersistenceCoordinator(
    private val tabRepository: TabRepository,
) {
    private var restorationComplete = false
    private var persistenceBound = false

    suspend fun restoreTabs(
        homepageUrl: String,
        browserSessionController: BrowserSessionController,
    ): String {
        val (persistedTabs, selectedIndex) = withContext(Dispatchers.IO) {
            val (persistedTabStates, restoredSelectedTabId) = tabRepository.loadTabsForRestoration()

            val restoredTabs = persistedTabStates.map { tabState ->
                val tabId = tabState.tabId.ifBlank { java.util.UUID.randomUUID().toString() }
                val thumbnail = tabRepository.loadTabThumbnail(tabId)
                PersistedBrowserTab(
                    url = tabState.url,
                    sessionState = tabState.sessionState,
                    title = tabState.title,
                    previewImageWebp = thumbnail ?: byteArrayOf(),
                    tabId = tabId,
                    openerTabId = tabState.openerTabId.ifBlank { null },
                    themeColor = tabState.themeColor,
                )
            }

            val restoredSelectedIndex = if (restoredSelectedTabId != null) {
                restoredTabs.indexOfFirst { it.tabId == restoredSelectedTabId }
                    .takeIf { it >= 0 } ?: 0
            } else {
                0
            }

            restoredTabs to restoredSelectedIndex
        }

        return withContext(Dispatchers.Main.immediate) {
            browserSessionController.restoreTabs(
                homepageUrl = homepageUrl,
                persistedTabs = persistedTabs,
                persistedSelectedTabIndex = selectedIndex,
            ).also {
                restorationComplete = true
            }
        }
    }

    fun bind(
        scope: CoroutineScope,
        browserSessionController: BrowserSessionController,
    ) {
        if (persistenceBound) {
            return
        }
        persistenceBound = true
        scope.launch {
            browserSessionController.tabStoreState.collectLatest {
                if (!restorationComplete) {
                    return@collectLatest
                }
                delay(500)
                saveInternal(browserSessionController)
            }
        }
    }

    private suspend fun saveInternal(
        browserSessionController: BrowserSessionController,
    ) {
        val tabs = browserSessionController.exportPersistedTabs()
        if (tabs.isEmpty()) {
            Log.w("TabPersistence", "タブリストが空のため保存をスキップ")
            return
        }

        val currentTabIds = tabs.map { it.tabId }.toSet()
        withContext(Dispatchers.IO) {
            tabs.forEach { tab ->
                if (tab.previewImageWebp.isNotEmpty()) {
                    tabRepository.saveTabThumbnail(tab.tabId, tab.previewImageWebp)
                }
            }
            tabRepository.deleteOrphanedThumbnails(currentTabIds)
        }

        runCatching {
            tabRepository.syncTabs(
                tabs = tabs.map { tab ->
                    PersistedTabState(
                        url = tab.url,
                        sessionState = tab.sessionState,
                        title = tab.title,
                        tabId = tab.tabId,
                        openerTabId = tab.openerTabId.orEmpty(),
                        themeColor = tab.themeColor,
                    )
                },
                selectedTabId = browserSessionController.selectedTabId,
            )
        }.onFailure { error ->
            Log.e("TabPersistence", "タブ状態の保存に失敗しました", error)
        }
    }
}

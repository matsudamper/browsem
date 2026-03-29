package net.matsudamper.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.TabRepository
import java.util.concurrent.ConcurrentHashMap

internal class BrowserTabPersistenceCoordinator(
    private val tabRepository: TabRepository,
    private val controllerScope: CoroutineScope,
) {
    private val persistenceMutex = Mutex()
    private val pendingCreatedTabIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingClosedTabIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun awaitIdle() {
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {}
        }
    }

    fun isPendingCreate(tabId: String): Boolean = tabId in pendingCreatedTabIds

    fun isPendingClose(tabId: String): Boolean = tabId in pendingClosedTabIds

    fun persistSelection(tabId: String?) {
        enqueue {
            it.selectTab(tabId)
        }
    }

    fun persistMoveTab(fromIndex: Int, toIndex: Int) {
        enqueue {
            it.moveTab(fromIndex, toIndex)
        }
    }

    fun persistCreatedTab(
        tab: BrowserTab,
        insertIndex: Int,
        selected: Boolean,
    ) {
        pendingCreatedTabIds.add(tab.tabId)
        pendingClosedTabIds.remove(tab.tabId)
        val persistedTab = tab.toPersistedTabState()
        enqueue {
            try {
                it.createOrUpdateTab(
                    tab = persistedTab,
                    insertIndex = insertIndex,
                    selected = selected,
                )
            } finally {
                pendingCreatedTabIds.remove(tab.tabId)
            }
        }
    }

    suspend fun persistCreatedTabNow(
        tab: BrowserTab,
        insertIndex: Int,
        selected: Boolean,
    ) {
        val persistedTab = tab.toPersistedTabState()
        pendingCreatedTabIds.add(tab.tabId)
        pendingClosedTabIds.remove(tab.tabId)
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {
                try {
                    tabRepository.createOrUpdateTab(
                        tab = persistedTab,
                        insertIndex = insertIndex,
                        selected = selected,
                    )
                } finally {
                    pendingCreatedTabIds.remove(tab.tabId)
                }
            }
        }
    }

    fun persistClosedTab(tabId: String, nextSelectedTabId: String?) {
        pendingClosedTabIds.add(tabId)
        pendingCreatedTabIds.remove(tabId)
        enqueue {
            try {
                it.closeTab(tabId, nextSelectedTabId)
            } finally {
                pendingClosedTabIds.remove(tabId)
            }
        }
    }

    fun persistUrl(tabId: String, value: String) {
        enqueue {
            it.updateUrl(tabId, value)
        }
    }

    fun persistSessionState(tabId: String, value: String) {
        enqueue {
            it.updateSessionState(tabId, value)
        }
    }

    fun persistTitle(tabId: String, value: String) {
        enqueue {
            it.updateTitle(tabId, value)
        }
    }

    fun persistThemeColor(tabId: String, value: Int?) {
        enqueue {
            it.updateThemeColor(tabId, value)
        }
    }

    fun persistPreviewBitmap(tabId: String, previewBitmap: ByteArray?) {
        controllerScope.launch(Dispatchers.IO) {
            if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                tabRepository.saveTabThumbnail(tabId, previewBitmap)
            }
        }
    }

    private fun enqueue(action: suspend (TabRepository) -> Unit) {
        controllerScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                runCatching {
                    action(tabRepository)
                }.onFailure { error ->
                    Log.e(TAG, "タブ状態の永続化に失敗しました", error)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "BrowserTabPersistence"
    }
}

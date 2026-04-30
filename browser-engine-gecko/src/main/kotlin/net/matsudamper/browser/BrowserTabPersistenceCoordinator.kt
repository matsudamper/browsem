package net.matsudamper.browser

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.TabRepository

internal class BrowserTabPersistenceCoordinator(
    tabRepository: TabRepository,
    private val controllerScope: CoroutineScope,
    private val isSinglePage: Boolean,
) {
    // CustomTabs等のTabに依存しない場合はTabの保存を利用しない
    private val tabRepository = tabRepository.takeUnless { isSinglePage }
    private val persistenceMutex = Mutex()

    suspend fun awaitIdle() {
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {}
        }
    }

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
        val persistedTab = tab.toPersistedTabState()
        enqueue {
            it.createOrUpdateTab(
                tab = persistedTab,
                insertIndex = insertIndex,
                selected = selected,
            )
        }
    }

    suspend fun persistCreatedTabNow(
        tab: BrowserTab,
        insertIndex: Int,
        selected: Boolean,
    ) {
        tabRepository ?: return
        val persistedTab = tab.toPersistedTabState()
        withContext(Dispatchers.IO) {
            persistenceMutex.withLock {
                tabRepository.createOrUpdateTab(
                    tab = persistedTab,
                    insertIndex = insertIndex,
                    selected = selected,
                )
            }
        }
    }

    fun persistClosedTab(tabId: String, nextSelectedTabId: String?) {
        enqueue {
            it.closeTab(tabId, nextSelectedTabId)
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
        if (previewBitmap == null || previewBitmap.isEmpty()) return
        enqueue {
            it.saveTabThumbnail(tabId, previewBitmap)
        }
    }

    private fun enqueue(action: suspend (TabRepository) -> Unit) {
        tabRepository ?: return
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

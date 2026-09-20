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
    private val persistenceScope: CoroutineScope,
    private val isSinglePage: Boolean,
) {
    // CustomTabs等のTabに依存しない場合はTabの保存を利用しない
    private val tabRepository = tabRepository.takeUnless { isSinglePage }

    /**
     * 保留中の保存と交差させたくない処理を直列化する。復元の読み出しに使う。
     */
    suspend fun <T> withPersistenceLock(block: suspend () -> T): T {
        return withContext(Dispatchers.IO) {
            persistenceMutex.withLock {
                block()
            }
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

    fun persistPageZoomPercent(tabId: String, value: Int) {
        enqueue {
            it.updatePageZoomPercent(tabId, value)
        }
    }

    fun persistPreviewBitmap(tabId: String, previewBitmap: ByteArray?) {
        tabRepository ?: return
        persistenceScope.launch(Dispatchers.IO) {
            if (previewBitmap != null && previewBitmap.isNotEmpty()) {
                runCatching {
                    tabRepository.saveTabThumbnail(tabId, previewBitmap)
                }.onFailure { error ->
                    Log.e(TAG, "タブプレビュー保存に失敗しました", error)
                }
            }
        }
    }

    private fun enqueue(action: suspend (TabRepository) -> Unit) {
        tabRepository ?: return
        persistenceScope.launch(Dispatchers.IO) {
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

        // Activity の作り直しで Controller が入れ替わっても保存と復元の順序を保てるよう、
        // 直列化はプロセス全体で共有する。
        private val persistenceMutex = Mutex()
    }
}

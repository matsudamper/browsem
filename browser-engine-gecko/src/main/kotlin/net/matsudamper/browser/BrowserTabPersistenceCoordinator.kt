package net.matsudamper.browser

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.TabRepository

internal class BrowserTabPersistenceCoordinator(
    tabRepository: TabRepository,
    persistenceScope: CoroutineScope,
    private val isSinglePage: Boolean,
) {
    // CustomTabs等のTabに依存しない場合はTabの保存を利用しない
    private val tabRepository = tabRepository.takeUnless { isSinglePage }

    @Volatile
    private var acceptsNewPersistence = true

    init {
        startWorkerIfNeeded(persistenceScope)
    }

    /**
     * Controller の終了後に新しい保存を受け付けなくする。
     * 終了処理中の delegate callback による保存が、作り直された Controller の復元結果を
     * 後から上書きしないようにする。キュー済みの保存はそのまま流し切る。
     */
    fun stopAcceptingNewPersistence() {
        acceptsNewPersistence = false
    }

    /**
     * 保留中の保存と交差させたくない処理を、保存と同じ順序で実行する。復元の読み出しに使う。
     */
    suspend fun <T> withPersistenceLock(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        // 中断を挟まずに登録し、呼び出し順がそのままキューの順序になるようにする
        persistenceTasks.trySend {
            runCatching { block() }
                .fold(result::complete, result::completeExceptionally)
        }
        return result.await()
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
        val tabRepository = tabRepository ?: return
        if (!acceptsNewPersistence) return
        val persistedTab = tab.toPersistedTabState()
        withPersistenceLock {
            tabRepository.createOrUpdateTab(
                tab = persistedTab,
                insertIndex = insertIndex,
                selected = selected,
            )
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
        if (previewBitmap == null || previewBitmap.isEmpty()) return
        enqueue {
            it.saveTabThumbnail(tabId, previewBitmap)
        }
    }

    private fun enqueue(action: suspend (TabRepository) -> Unit) {
        val tabRepository = tabRepository ?: return
        if (!acceptsNewPersistence) return
        // send ではなく trySend で、呼び出し順がそのままキューの順序になるようにする
        persistenceTasks.trySend {
            runCatching {
                action(tabRepository)
            }.onFailure { error ->
                Log.e(TAG, "タブ状態の永続化に失敗しました", error)
            }
        }
    }

    private companion object {
        private const val TAG = "BrowserTabPersistence"

        // Activity の作り直しで Controller が入れ替わっても保存と復元の順序を保てるよう、
        // キューはプロセス全体で共有し、単一のコルーチンが投入順に実行する。
        private val persistenceTasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private var workerStarted = false

        @Synchronized
        private fun startWorkerIfNeeded(scope: CoroutineScope) {
            if (workerStarted) return
            workerStarted = true
            scope.launch(Dispatchers.IO) {
                for (task in persistenceTasks) {
                    task()
                }
            }
        }
    }
}

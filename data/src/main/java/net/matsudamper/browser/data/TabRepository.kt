package net.matsudamper.browser.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.Flow
import java.io.File

private val Context.browserTabDataStore: DataStore<BrowserTabData> by dataStore(
    fileName = "browser_tab_state.pb",
    serializer = BrowserTabDataSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { BrowserTabData.getDefaultInstance() },
)

class TabRepository(context: Context) {
    private val dataStore = context.browserTabDataStore
    private val thumbnailDir = File(context.cacheDir, "tab_thumbnails").apply { mkdirs() }

    val tabs: Flow<BrowserTabData> = dataStore.data

    /** サムネイル画像をキャッシュファイルに保存する */
    fun saveTabThumbnail(tabId: String, imageBytes: ByteArray) {
        if (imageBytes.isEmpty()) return
        File(thumbnailDir, "$tabId.webp").writeBytes(imageBytes)
    }

    /** サムネイル画像をキャッシュファイルから読み込む */
    fun loadTabThumbnail(tabId: String): ByteArray? {
        val file = File(thumbnailDir, "$tabId.webp")
        return if (file.exists()) file.readBytes() else null
    }

    /** 現在存在しないタブのサムネイルファイルを削除する */
    fun deleteOrphanedThumbnails(validTabIds: Set<String>) {
        thumbnailDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in validTabIds) {
                file.delete()
            }
        }
    }

    suspend fun updateTabStates(
        tabs: List<PersistedTabState>,
        selectedTabIndex: Int,
    ) {
        // 空リストでの保存を拒否（既存データの消失を防止）
        if (tabs.isEmpty()) {
            Log.w("TabRepository", "空のタブリストは保存しない")
            return
        }
        val clampedIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)
        try {
            dataStore.updateData { current ->
                val currentTabs = current.tabStatesList.map {
                    PersistedTabState(
                        url = it.url,
                        sessionState = it.sessionState,
                        title = it.title,
                        tabId = it.tabId,
                        openerTabId = it.openerTabId,
                    )
                }
                if (currentTabs == tabs && current.selectedTabIndex == clampedIndex) {
                    return@updateData current
                }
                val builder = current.toBuilder()
                builder.clearTabStates()
                tabs.forEach { tab ->
                    builder.addTabStates(
                        BrowserTabState.newBuilder()
                            .setUrl(tab.url)
                            .setSessionState(tab.sessionState)
                            .setTitle(tab.title)
                            .setTabId(tab.tabId)
                            .setOpenerTabId(tab.openerTabId)
                            .build()
                    )
                }
                builder.selectedTabIndex = clampedIndex
                builder.build()
            }
        } catch (e: Exception) {
            Log.e("TabRepository", "タブ状態の保存に失敗", e)
        }
    }
}

data class PersistedTabState(
    val url: String,
    val sessionState: String,
    val title: String,
    val tabId: String = "",
    val openerTabId: String = "",
)

package net.matsudamper.browser.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow

private val Context.browserTabDataStore: DataStore<BrowserTabData> by dataStore(
    fileName = "browser_tab_state.pb",
    serializer = BrowserTabDataSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { BrowserTabData.getDefaultInstance() },
)

class TabRepository(context: Context) {
    private val dataStore = context.browserTabDataStore

    val tabs: Flow<BrowserTabData> = dataStore.data

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
                        previewImageWebp = it.previewImageWebp,
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
                            .setPreviewImageWebp(tab.previewImageWebp)
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
    val previewImageWebp: ByteString = ByteString.EMPTY,
    val tabId: String = "",
    val openerTabId: String = "",
)

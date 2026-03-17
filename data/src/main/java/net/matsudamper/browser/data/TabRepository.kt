package net.matsudamper.browser.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.TabStateEntity
import java.io.File

class TabRepository(context: Context) {
    private val db = TabDatabase.getInstance(context)
    private val dao = db.tabDao()
    private val thumbnailDir = File(context.cacheDir, "tab_thumbnails").apply { mkdirs() }

    /** 起動時にDBからタブ一覧と選択タブIDを読み込む */
    suspend fun loadTabsForRestoration(): Pair<List<PersistedTabState>, String?> {
        val entities = dao.getAllTabs()
        val tabs = entities.map { it.toPersistedTabState() }
        val selectedTabId = entities.firstOrNull { it.isSelected == 1 }?.tabId
            ?: entities.lastOrNull()?.tabId
        return Pair(tabs, selectedTabId)
    }

    /**
     * 現在のタブ一覧と選択タブをDBに同期する。
     * 差分を比較し、変更があった部分のみ更新する。
     */
    suspend fun syncTabs(tabs: List<PersistedTabState>, selectedTabId: String?) {
        if (tabs.isEmpty()) {
            Log.w("TabRepository", "空のタブリストは保存しない")
            return
        }
        db.withTransaction {
            val currentEntities = dao.getAllTabs().associateBy { it.tabId }
            val currentTabIds = tabs.map { it.tabId }.toSet()

            // 削除されたタブをDBから消す
            val deletedTabIds = currentEntities.keys - currentTabIds
            deletedTabIds.forEach { dao.deleteTab(it) }

            // 変更または新規タブのみを保存する
            tabs.forEachIndexed { index, tab ->
                val existing = currentEntities[tab.tabId]
                if (existing == null) {
                    dao.upsertTab(tab.toEntity(sortOrder = index, isSelected = 0))
                } else {
                    if (existing.url != tab.url) dao.updateUrl(tab.tabId, tab.url)
                    if (existing.title != tab.title) dao.updateTitle(tab.tabId, tab.title)
                    if (existing.sessionState != tab.sessionState) dao.updateSessionState(tab.tabId, tab.sessionState)
                    if (existing.themeColor != tab.themeColor) dao.updateThemeColor(tab.tabId, tab.themeColor)
                    if (existing.sortOrder != index) dao.updateSortOrder(tab.tabId, index)
                }
            }

            // 選択タブが変わった場合のみ更新する
            if (selectedTabId != null) {
                val currentSelectedId = currentEntities.values.firstOrNull { it.isSelected == 1 }?.tabId
                if (currentSelectedId != selectedTabId) {
                    dao.setSelectedTab(selectedTabId)
                }
            }
        }
    }

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

    private fun TabStateEntity.toPersistedTabState() = PersistedTabState(
        url = url,
        sessionState = sessionState,
        title = title,
        tabId = tabId,
        openerTabId = openerTabId,
        themeColor = themeColor,
    )

    private fun PersistedTabState.toEntity(sortOrder: Int, isSelected: Int) = TabStateEntity(
        tabId = tabId,
        url = url,
        sessionState = sessionState,
        title = title,
        openerTabId = openerTabId,
        themeColor = themeColor,
        sortOrder = sortOrder,
        isSelected = isSelected,
        groupId = "", // グループ管理は TabGroupRepository が担う
    )
}

data class PersistedTabState(
    val url: String,
    val sessionState: String,
    val title: String,
    val tabId: String = "",
    val openerTabId: String = "",
    val themeColor: Int? = null,
)

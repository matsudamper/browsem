package net.matsudamper.browser.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.TabStateEntity

class TabRepository(context: Context) {
    private val db = TabDatabase.getInstance(context)
    private val dao = db.tabDao()
    private val thumbnailDir = File(context.cacheDir, "tab_thumbnails").apply { mkdirs() }

    fun observeTabs(): Flow<PersistedTabStateContainer> {
        return dao.observeAllTabs().map { entities ->
            entities.toPersistedTabStateContainer()
        }
    }

    suspend fun loadTabs(): PersistedTabStateContainer {
        return dao.getAllTabs().toPersistedTabStateContainer()
    }

    suspend fun createOrUpdateTab(
        tab: PersistedTabState,
        insertIndex: Int,
        selected: Boolean,
    ) {
        db.withTransaction {
            val currentEntities = dao.getAllTabs()
            val existing = currentEntities.firstOrNull { it.tabId == tab.tabId }
            val visibleTabs = currentEntities.filterNot(TabStateEntity::isPlaceholderForPreAssignment)
            val targetIndex = insertIndex.coerceIn(0, visibleTabs.size)

            dao.upsertTab(
                TabStateEntity(
                    tabId = tab.tabId,
                    url = tab.url,
                    sessionState = tab.sessionState,
                    title = tab.title,
                    openerTabId = tab.openerTabId,
                    themeColor = tab.themeColor,
                    sortOrder = existing?.sortOrder ?: targetIndex,
                    isSelected = when {
                        selected -> 1
                        existing != null -> existing.isSelected
                        else -> 0
                    },
                    groupId = existing?.groupId.orEmpty(),
                ),
            )

            reorderVisibleTabs(moveTabId = tab.tabId, targetIndex = targetIndex)

            if (selected) {
                dao.setSelectedTab(tab.tabId)
            }
        }
    }

    suspend fun updateUrl(tabId: String, url: String) {
        dao.updateUrl(tabId, url)
    }

    suspend fun updateTitle(tabId: String, title: String) {
        dao.updateTitle(tabId, title)
    }

    suspend fun updateSessionState(tabId: String, sessionState: String) {
        dao.updateSessionState(tabId, sessionState)
    }

    suspend fun updateThemeColor(tabId: String, themeColor: Int?) {
        dao.updateThemeColor(tabId, themeColor)
    }

    suspend fun selectTab(tabId: String?) {
        db.withTransaction {
            if (tabId == null) {
                dao.clearSelectedTab()
            } else {
                dao.setSelectedTab(tabId)
            }
        }
    }

    suspend fun moveTab(fromIndex: Int, toIndex: Int) {
        db.withTransaction {
            val visibleTabs = dao.getAllTabs()
                .filterNot(TabStateEntity::isPlaceholderForPreAssignment)
                .toMutableList()
            if (fromIndex !in visibleTabs.indices || toIndex !in visibleTabs.indices) {
                return@withTransaction
            }
            visibleTabs.add(toIndex, visibleTabs.removeAt(fromIndex))
            visibleTabs.forEachIndexed { index, entity ->
                if (entity.sortOrder != index) {
                    dao.updateSortOrder(entity.tabId, index)
                }
            }
        }
    }

    suspend fun closeTab(tabId: String, nextSelectedTabId: String?) {
        db.withTransaction {
            dao.deleteTab(tabId)
            if (nextSelectedTabId == null) {
                dao.clearSelectedTab()
            } else {
                dao.setSelectedTab(nextSelectedTabId)
            }
            reorderVisibleTabs()
        }
        deleteTabThumbnail(tabId)
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

    fun deleteTabThumbnail(tabId: String) {
        File(thumbnailDir, "$tabId.webp").delete()
    }

    private suspend fun reorderVisibleTabs(
        moveTabId: String? = null,
        targetIndex: Int? = null,
    ) {
        val visibleTabs = dao.getAllTabs()
            .filterNot(TabStateEntity::isPlaceholderForPreAssignment)
            .toMutableList()

        if (moveTabId != null && targetIndex != null) {
            val currentIndex = visibleTabs.indexOfFirst { it.tabId == moveTabId }
            if (currentIndex >= 0) {
                val entity = visibleTabs.removeAt(currentIndex)
                visibleTabs.add(targetIndex.coerceIn(0, visibleTabs.size), entity)
            }
        }

        visibleTabs.forEachIndexed { index, entity ->
            if (entity.sortOrder != index) {
                dao.updateSortOrder(entity.tabId, index)
            }
        }
    }

    private fun List<TabStateEntity>.toPersistedTabStateContainer(): PersistedTabStateContainer {
        val visibleEntities = filterNot(TabStateEntity::isPlaceholderForPreAssignment)
        val selectedTabId = visibleEntities.firstOrNull { it.isSelected == 1 }?.tabId
            ?: visibleEntities.lastOrNull()?.tabId
        return PersistedTabStateContainer(
            tabs = visibleEntities.map { it.toPersistedTabState() },
            selectedTabId = selectedTabId,
        )
    }

    private fun TabStateEntity.toPersistedTabState() = PersistedTabState(
        url = url,
        sessionState = sessionState,
        title = title,
        tabId = tabId,
        openerTabId = openerTabId,
        themeColor = themeColor,
    )
}

data class PersistedTabStateContainer(
    val tabs: List<PersistedTabState>,
    val selectedTabId: String?,
)

data class PersistedTabState(
    val url: String,
    val sessionState: String,
    val title: String,
    val tabId: String = "",
    val openerTabId: String = "",
    val themeColor: Int? = null,
)

private fun TabStateEntity.isPlaceholderForPreAssignment(): Boolean {
    return url.isBlank() &&
        title.isBlank() &&
        sessionState.isBlank() &&
        openerTabId.isBlank() &&
        themeColor == null
}

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

    // サムネイルはタブの永続データなので、消えうる cacheDir ではなく filesDir に置く。
    // 以前は cacheDir に保存していたため、旧ディレクトリからのマイグレーションを行う。
    private val thumbnailDir = File(context.filesDir, "tab_thumbnails").apply { mkdirs() }

    init {
        val oldDir = File(context.cacheDir, "tab_thumbnails")
        if (oldDir.exists()) {
            oldDir.listFiles()?.forEach { file ->
                val dest = File(thumbnailDir, file.name)
                if (!dest.exists()) {
                    file.renameTo(dest)
                } else {
                    file.delete()
                }
            }
            oldDir.delete()
        }
    }

    // sessionState は GeckoView のセッション状態(履歴・フォーム・スクロール等)を直列化した
    // 実質 blob で、ヘビーに使われたタブでは Android の CursorWindow 上限(約2MB)を超え、
    // tab_state の取得時に SQLiteBlobTooBigException でクラッシュする原因になっていた。
    // そのため DB には保持せず、tabId をファイル名としてファイルへ保存する。
    // 履歴等を含む永続データなので、消えうる cacheDir ではなく filesDir に置く。
    private val sessionStateDir = File(context.filesDir, "tab_session_states").apply { mkdirs() }

    private fun sessionStateFile(tabId: String) = File(sessionStateDir, tabId)

    /** sessionState をファイルへ保存する。空の場合はファイルを削除する */
    private fun writeSessionState(tabId: String, sessionState: String) {
        val file = sessionStateFile(tabId)
        if (sessionState.isBlank()) {
            file.delete()
            return
        }
        if (!sessionStateDir.exists()) {
            sessionStateDir.mkdirs()
        }
        file.writeText(sessionState)
    }

    /** 指定タブの sessionState をファイルから読み出す。無ければ空文字。 */
    private fun readSessionState(tabId: String): String {
        val file = sessionStateFile(tabId)
        return if (file.exists()) file.readText() else ""
    }

    private fun deleteSessionStateFile(tabId: String) {
        sessionStateFile(tabId).delete()
    }

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
                    pageZoomPercent = tab.pageZoomPercent,
                ),
            )

            reorderVisibleTabs(moveTabId = tab.tabId, targetIndex = targetIndex)

            if (selected) {
                dao.setSelectedTab(tab.tabId)
            }
        }
        // sessionState はファイルへ保存する（DB トランザクション外で I/O を行う）
        writeSessionState(tab.tabId, tab.sessionState)
    }

    suspend fun updateUrl(tabId: String, url: String) {
        dao.updateUrl(tabId, url)
    }

    suspend fun updateTitle(tabId: String, title: String) {
        dao.updateTitle(tabId, title)
    }

    suspend fun updateSessionState(tabId: String, sessionState: String) {
        // sessionState は DB ではなくファイルに保存する
        writeSessionState(tabId, sessionState)
    }

    suspend fun updateThemeColor(tabId: String, themeColor: Int?) {
        dao.updateThemeColor(tabId, themeColor)
    }

    suspend fun updatePageZoomPercent(tabId: String, pageZoomPercent: Int) {
        dao.updatePageZoomPercent(tabId, pageZoomPercent)
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
        deleteSessionStateFile(tabId)
    }

    /** サムネイル画像をファイルに保存する */
    fun saveTabThumbnail(tabId: String, imageBytes: ByteArray) {
        if (imageBytes.isEmpty()) return
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }
        File(thumbnailDir, "$tabId.webp").writeBytes(imageBytes)
    }

    /** サムネイル画像をファイルから読み込む */
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
        sessionState = readSessionState(tabId),
        title = title,
        tabId = tabId,
        openerTabId = openerTabId,
        themeColor = themeColor,
        pageZoomPercent = pageZoomPercent,
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
    val pageZoomPercent: Int = 100,
)

private fun TabStateEntity.isPlaceholderForPreAssignment(): Boolean {
    // グループ先行割り当てのプレースホルダ行は全フィールドが空。
    // 実タブは必ず非空の url を持つため、これで一意に識別できる。
    return url.isBlank() &&
        title.isBlank() &&
        openerTabId.isBlank() &&
        themeColor == null
}

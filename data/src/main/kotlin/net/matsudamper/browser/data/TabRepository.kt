package net.matsudamper.browser.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.TabStateEntity
import net.matsudamper.browser.data.tab.TabStateRow

class TabRepository(context: Context) {
    private val db = TabDatabase.getInstance(context)
    private val dao = db.tabDao()
    private val thumbnailDir = File(context.cacheDir, "tab_thumbnails").apply { mkdirs() }

    // sessionState は GeckoView のセッション状態(履歴・フォーム・スクロール等)を直列化した
    // 実質 blob で、ヘビーに使われたタブでは Android の CursorWindow 上限(約2MB)を超え、
    // tab_state の取得時に SQLiteBlobTooBigException でクラッシュする原因になる。
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

    private fun deleteSessionStateFile(tabId: String) {
        sessionStateFile(tabId).delete()
    }

    /**
     * 指定タブの sessionState を解決する。
     * ファイルがあればそれを返す。無い場合は旧バージョンで DB 列に保存された
     * sessionState をファイルへコピーしてから返す。
     * ファイル移行が正しく動くと確認できるまでは、安全のため DB 列のデータは消さずに残す。
     * （DB 列データの削除と移行コードの撤去は別 Issue で対応する）
     */
    private suspend fun resolveSessionState(tabId: String): String {
        val file = sessionStateFile(tabId)
        if (file.exists()) {
            return file.readText()
        }
        val fromDb = readSessionStateFromDb(tabId)
        if (fromDb.isNotEmpty()) {
            // DB のデータはバックアップとして温存し、ファイルへコピーするだけに留める
            writeSessionState(tabId, fromDb)
        }
        return fromDb
    }

    /**
     * 旧バージョンで tab_state.sessionState に保存された文字列を読み出す。
     * 巨大セルでも CursorWindow 上限(約2MB)を超えないよう substr で分割して読む。
     */
    private suspend fun readSessionStateFromDb(tabId: String): String {
        val length = dao.getSessionStateLength(tabId) ?: return ""
        if (length <= 0) return ""
        val builder = StringBuilder(length)
        var offset = 1 // SQLite の substr は 1 始まり
        while (offset <= length) {
            val chunk = dao.getSessionStateChunk(tabId, offset, SESSION_STATE_CHUNK_CHARS)
            if (chunk.isNullOrEmpty()) break
            builder.append(chunk)
            offset += SESSION_STATE_CHUNK_CHARS
        }
        return builder.toString()
    }

    fun observeTabs(): Flow<PersistedTabStateContainer> {
        return dao.observeAllTabs().map { rows ->
            rows.toPersistedTabStateContainer()
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
            val currentRows = dao.getAllTabs()
            val existing = currentRows.firstOrNull { it.tabId == tab.tabId }
            val visibleTabs = currentRows.filterNot(TabStateRow::isPlaceholderForPreAssignment)
            val targetIndex = insertIndex.coerceIn(0, visibleTabs.size)

            dao.upsertTab(
                TabStateEntity(
                    tabId = tab.tabId,
                    url = tab.url,
                    // sessionState はファイルへ保存するため DB 列は常に空にする
                    sessionState = "",
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
                .filterNot(TabStateRow::isPlaceholderForPreAssignment)
                .toMutableList()
            if (fromIndex !in visibleTabs.indices || toIndex !in visibleTabs.indices) {
                return@withTransaction
            }
            visibleTabs.add(toIndex, visibleTabs.removeAt(fromIndex))
            visibleTabs.forEachIndexed { index, row ->
                if (row.sortOrder != index) {
                    dao.updateSortOrder(row.tabId, index)
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

    /** サムネイル画像をキャッシュファイルに保存する */
    fun saveTabThumbnail(tabId: String, imageBytes: ByteArray) {
        if (imageBytes.isEmpty()) return
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }
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
            .filterNot(TabStateRow::isPlaceholderForPreAssignment)
            .toMutableList()

        if (moveTabId != null && targetIndex != null) {
            val currentIndex = visibleTabs.indexOfFirst { it.tabId == moveTabId }
            if (currentIndex >= 0) {
                val row = visibleTabs.removeAt(currentIndex)
                visibleTabs.add(targetIndex.coerceIn(0, visibleTabs.size), row)
            }
        }

        visibleTabs.forEachIndexed { index, row ->
            if (row.sortOrder != index) {
                dao.updateSortOrder(row.tabId, index)
            }
        }
    }

    private suspend fun List<TabStateRow>.toPersistedTabStateContainer(): PersistedTabStateContainer {
        val visibleRows = filterNot(TabStateRow::isPlaceholderForPreAssignment)
        val selectedTabId = visibleRows.firstOrNull { it.isSelected == 1 }?.tabId
            ?: visibleRows.lastOrNull()?.tabId
        return PersistedTabStateContainer(
            tabs = visibleRows.map { it.toPersistedTabState() },
            selectedTabId = selectedTabId,
        )
    }

    private suspend fun TabStateRow.toPersistedTabState() = PersistedTabState(
        url = url,
        sessionState = resolveSessionState(tabId),
        title = title,
        tabId = tabId,
        openerTabId = openerTabId,
        themeColor = themeColor,
    )

    private companion object {
        /**
         * 旧 DB から sessionState を分割読みする際の 1 チャンクの文字数。
         * 最悪 4byte/文字でも約 1MB に収まり、CursorWindow 上限(約2MB)を超えない。
         */
        private const val SESSION_STATE_CHUNK_CHARS = 256 * 1024
    }
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

private fun TabStateRow.isPlaceholderForPreAssignment(): Boolean {
    // グループ先行割り当てのプレースホルダ行は全フィールドが空。
    // sessionState はファイルへ移行したため、ここでは射影に含まれるフィールドのみで判定する。
    // 実タブは必ず非空の url を持つため、これで一意に識別できる。
    return url.isBlank() &&
        title.isBlank() &&
        openerTabId.isBlank() &&
        themeColor == null
}

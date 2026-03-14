package net.matsudamper.browser.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.TabGroupAssignment
import net.matsudamper.browser.data.tab.TabGroupEntity

class TabGroupRepository(context: Context) {
    private val db = TabDatabase.getInstance(context)
    private val dao = db.tabGroupDao()

    /** グループ一覧を Flow で購読する */
    fun observeGroups(): Flow<List<TabGroupData>> {
        return dao.observeGroups().map { entities ->
            entities.map { TabGroupData(TabGroupId(it.groupId), it.name) }
        }
    }

    /** タブID→グループIDのマッピングを Flow で購読する */
    fun observeTabGroupAssignments(): Flow<List<TabGroupAssignment>> {
        return dao.observeTabGroupAssignments()
    }

    /**
     * 初期グループを作成する。
     * DB が空のときのみデフォルトグループを作成し、既存のすべてのタブをそのグループに割り当てる。
     * DB に既にグループが存在する場合は先頭グループのIDを返す。
     */
    suspend fun createDefaultGroupIfEmpty(tabIds: List<String>): TabGroupId {
        val existing = dao.getAllGroups()
        if (existing.isNotEmpty()) {
            val firstId = TabGroupId(existing.first().groupId)
            // グループ未割当タブをデフォルトグループに割り当て
            tabIds.forEach { tabId -> dao.setTabGroup(tabId, firstId.value) }
            return firstId
        }
        val id = TabGroupId.generate()
        dao.upsertGroup(TabGroupEntity(groupId = id.value, name = "デフォルト", sortOrder = 0))
        tabIds.forEach { tabId -> dao.setTabGroup(tabId, id.value) }
        return id
    }

    /** 新グループを追加する */
    suspend fun addGroup(name: String, sortOrder: Int): TabGroupId {
        val id = TabGroupId.generate()
        dao.upsertGroup(TabGroupEntity(groupId = id.value, name = name, sortOrder = sortOrder))
        return id
    }

    /** タブをグループに割り当てる */
    suspend fun assignTabToGroup(tabId: String, groupId: TabGroupId) {
        dao.setTabGroup(tabId, groupId.value)
    }

    /** タブのグループ割り当てを空文字に設定する（タブ削除時） */
    suspend fun removeTabFromGroup(tabId: String) {
        dao.setTabGroup(tabId, "")
    }
}

data class TabGroupData(val id: TabGroupId, val name: String)

package net.matsudamper.browser.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.TabGroupAssignment
import net.matsudamper.browser.data.tab.TabGroupEntity

interface TabGroupRepository {
    /** グループ一覧を Flow で購読する */
    fun observeGroups(): Flow<List<TabGroupData>>

    /** タブID→グループIDのマッピングを Flow で購読する */
    fun observeTabGroupAssignments(): Flow<List<TabGroupAssignment>>

    /**
     * 初期グループを作成する。
     * DB が空のときのみデフォルトグループを作成し、既存のすべてのタブをそのグループに割り当てる。
     * DB に既にグループが存在する場合は先頭グループのIDを返す。
     */
    suspend fun createDefaultGroupIfEmpty(tabIds: List<String>): TabGroupId

    /** 新グループを追加する */
    suspend fun addGroup(name: String, sortOrder: Int): TabGroupId

    /** タブをグループに割り当てる */
    suspend fun assignTabToGroup(tabId: String, groupId: TabGroupId)

    /** タブのグループ割り当てを空文字に設定する（タブ削除時） */
    suspend fun removeTabFromGroup(tabId: String)

    /** グループの並び順を更新する */
    suspend fun reorderGroups(orderedGroupIds: List<String>)

    /** グループ名を変更する */
    suspend fun renameGroup(groupId: TabGroupId, name: String)

    /**
     * グループを削除する。
     * fallbackGroupId が指定された場合、削除前にそのグループへタブを再割り当てする。
     */
    suspend fun deleteGroup(groupId: TabGroupId, fallbackGroupId: TabGroupId?)

    /**
     * グループのデフォルト設定を変更する。
     * isDefault = true の場合は他のグループのデフォルトをすべて解除してから設定する。
     */
    suspend fun setDefaultGroup(groupId: TabGroupId, isDefault: Boolean)

    /** デフォルトに設定されているグループIDを返す。設定されていない場合は null。 */
    suspend fun getDefaultGroupId(): TabGroupId?
}

class TabGroupRepositoryImpl(context: Context) : TabGroupRepository {
    private val db = TabDatabase.getInstance(context)
    private val dao = db.tabGroupDao()

    override fun observeGroups(): Flow<List<TabGroupData>> {
        return dao.observeGroups().map { entities ->
            entities.map { TabGroupData(TabGroupId(it.groupId), it.name, it.isDefault) }
        }
    }

    override fun observeTabGroupAssignments(): Flow<List<TabGroupAssignment>> {
        return dao.observeTabGroupAssignments()
    }

    override suspend fun createDefaultGroupIfEmpty(tabIds: List<String>): TabGroupId {
        val existing = dao.getAllGroups()
        if (existing.isNotEmpty()) {
            val firstId = TabGroupId(existing.first().groupId)
            // グループ未割当タブ（groupId が空）のみデフォルトグループに割り当て
            // 既に別グループに割り当て済みのタブは触らない
            val unassignedTabIds = dao.getUnassignedTabIds()
            unassignedTabIds.forEach { tabId -> dao.setTabGroup(tabId, firstId.value) }
            return firstId
        }
        val id = TabGroupId.generate()
        dao.upsertGroup(TabGroupEntity(groupId = id.value, name = "デフォルト", sortOrder = 0))
        tabIds.forEach { tabId -> dao.setTabGroup(tabId, id.value) }
        return id
    }

    override suspend fun addGroup(name: String, sortOrder: Int): TabGroupId {
        val id = TabGroupId.generate()
        dao.upsertGroup(TabGroupEntity(groupId = id.value, name = name, sortOrder = sortOrder))
        return id
    }

    override suspend fun assignTabToGroup(tabId: String, groupId: TabGroupId) {
        dao.setTabGroup(tabId, groupId.value)
    }

    override suspend fun removeTabFromGroup(tabId: String) {
        // setTabGroup (INSERT IGNORE + UPDATE) は削除済みタブに幽霊レコードを生成するため、
        // UPDATE のみ行う updateTabGroup を使う
        dao.updateTabGroup(tabId, "")
    }

    override suspend fun reorderGroups(orderedGroupIds: List<String>) {
        orderedGroupIds.forEachIndexed { index, groupId ->
            dao.updateSortOrder(groupId, index)
        }
    }

    override suspend fun renameGroup(groupId: TabGroupId, name: String) {
        dao.updateGroupName(groupId.value, name)
    }

    override suspend fun deleteGroup(groupId: TabGroupId, fallbackGroupId: TabGroupId?) {
        if (fallbackGroupId != null) {
            dao.reassignTabsFromGroup(groupId.value, fallbackGroupId.value)
        }
        dao.deleteGroup(groupId.value)
    }

    override suspend fun setDefaultGroup(groupId: TabGroupId, isDefault: Boolean) {
        dao.setDefaultGroup(groupId.value, isDefault)
    }

    override suspend fun getDefaultGroupId(): TabGroupId? {
        return dao.getAllGroups().firstOrNull { it.isDefault }?.let { TabGroupId(it.groupId) }
    }
}

data class TabGroupData(val id: TabGroupId, val name: String, val isDefault: Boolean = false)

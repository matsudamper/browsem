package net.matsudamper.browser.data.tab

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TabGroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertGroup(group: TabGroupEntity)

    @Query("SELECT * FROM tab_group ORDER BY sortOrder ASC")
    abstract fun observeGroups(): Flow<List<TabGroupEntity>>

    @Query("SELECT * FROM tab_group ORDER BY sortOrder ASC")
    abstract suspend fun getAllGroups(): List<TabGroupEntity>

    @Query("DELETE FROM tab_group WHERE groupId = :groupId")
    abstract suspend fun deleteGroup(groupId: String)

    @Query("UPDATE tab_state SET groupId = :newGroupId WHERE groupId = :oldGroupId")
    abstract suspend fun reassignTabsFromGroup(oldGroupId: String, newGroupId: String)

    @Query("UPDATE tab_state SET groupId = :groupId WHERE tabId = :tabId")
    abstract suspend fun updateTabGroup(tabId: String, groupId: String)

    /**
     * tab_state 行がまだ存在しない場合（TabPersistenceCoordinator の 500ms デバウンス待ち）に
     * プレースホルダ行を作成する。既に行がある場合は IGNORE で何もしない。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTabIfNotExists(tab: TabStateEntity)

    /**
     * タブをグループに割り当てる。
     * tab_state 行が存在しない場合はプレースホルダ行を INSERT してから UPDATE する。
     * これにより、TabPersistenceCoordinator が行を作成する前でも割り当てが成功する。
     */
    @Transaction
    open suspend fun setTabGroup(tabId: String, groupId: String) {
        insertTabIfNotExists(
            TabStateEntity(
                tabId = tabId,
                url = "",
                sessionState = "",
                title = "",
                openerTabId = "",
                themeColor = null,
                sortOrder = 0,
                isSelected = 0,
                groupId = groupId,
            ),
        )
        updateTabGroup(tabId, groupId)
    }

    @Query("UPDATE tab_group SET sortOrder = :sortOrder WHERE groupId = :groupId")
    abstract suspend fun updateSortOrder(groupId: String, sortOrder: Int)

    /** タブID→グループIDのマッピングを Flow で購読する */
    @Query("SELECT tabId, groupId FROM tab_state")
    abstract fun observeTabGroupAssignments(): Flow<List<TabGroupAssignment>>

    /** グループ未割当（groupId が空）のタブIDを取得する */
    @Query("SELECT tabId FROM tab_state WHERE groupId = '' OR groupId IS NULL")
    abstract suspend fun getUnassignedTabIds(): List<String>
}

data class TabGroupAssignment(val tabId: String, val groupId: String)

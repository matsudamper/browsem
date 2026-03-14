package net.matsudamper.browser.data.tab

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TabGroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: TabGroupEntity)

    @Query("SELECT * FROM tab_group ORDER BY sortOrder ASC")
    fun observeGroups(): Flow<List<TabGroupEntity>>

    @Query("SELECT * FROM tab_group ORDER BY sortOrder ASC")
    suspend fun getAllGroups(): List<TabGroupEntity>

    @Query("DELETE FROM tab_group WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE tab_state SET groupId = :newGroupId WHERE groupId = :oldGroupId")
    suspend fun reassignTabsFromGroup(oldGroupId: String, newGroupId: String)

    @Query("UPDATE tab_state SET groupId = :groupId WHERE tabId = :tabId")
    suspend fun setTabGroup(tabId: String, groupId: String)

    /** タブID→グループIDのマッピングを Flow で購読する */
    @Query("SELECT tabId, groupId FROM tab_state")
    fun observeTabGroupAssignments(): Flow<List<TabGroupAssignment>>

    @Query("DELETE FROM tab_group")
    suspend fun deleteAll()
}

data class TabGroupAssignment(val tabId: String, val groupId: String)

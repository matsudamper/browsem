package net.matsudamper.browser.data.tab

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TabDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTab(tab: TabStateEntity)

    @Query("SELECT * FROM tab_state ORDER BY sortOrder ASC")
    suspend fun getAllTabs(): List<TabStateEntity>

    @Query("DELETE FROM tab_state WHERE tabId = :tabId")
    suspend fun deleteTab(tabId: String)

    @Query("UPDATE tab_state SET url = :url WHERE tabId = :tabId")
    suspend fun updateUrl(tabId: String, url: String)

    @Query("UPDATE tab_state SET title = :title WHERE tabId = :tabId")
    suspend fun updateTitle(tabId: String, title: String)

    @Query("UPDATE tab_state SET sessionState = :sessionState WHERE tabId = :tabId")
    suspend fun updateSessionState(tabId: String, sessionState: String)

    @Query("UPDATE tab_state SET themeColor = :themeColor WHERE tabId = :tabId")
    suspend fun updateThemeColor(tabId: String, themeColor: Int?)

    @Query("UPDATE tab_state SET sortOrder = :sortOrder WHERE tabId = :tabId")
    suspend fun updateSortOrder(tabId: String, sortOrder: Int)

    /** 指定したタブを選択中にし、他は未選択にする */
    @Query("UPDATE tab_state SET isSelected = CASE WHEN tabId = :tabId THEN 1 ELSE 0 END")
    suspend fun setSelectedTab(tabId: String)

    @Query("DELETE FROM tab_state")
    suspend fun deleteAll()
}

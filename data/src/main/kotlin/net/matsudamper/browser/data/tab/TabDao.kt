package net.matsudamper.browser.data.tab

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTab(tab: TabStateEntity)

    // sessionState は CursorWindow 上限(約2MB)を超え得るため一覧取得では読まない。
    // SELECT * を避け、sessionState 以外のメタデータのみを射影して取得する。
    @Query(
        "SELECT tabId, url, title, openerTabId, themeColor, sortOrder, isSelected, groupId " +
            "FROM tab_state ORDER BY sortOrder ASC",
    )
    suspend fun getAllTabs(): List<TabStateRow>

    @Query(
        "SELECT tabId, url, title, openerTabId, themeColor, sortOrder, isSelected, groupId " +
            "FROM tab_state ORDER BY sortOrder ASC",
    )
    fun observeAllTabs(): Flow<List<TabStateRow>>

    @Query("DELETE FROM tab_state WHERE tabId = :tabId")
    suspend fun deleteTab(tabId: String)

    @Query("UPDATE tab_state SET url = :url WHERE tabId = :tabId")
    suspend fun updateUrl(tabId: String, url: String)

    @Query("UPDATE tab_state SET title = :title WHERE tabId = :tabId")
    suspend fun updateTitle(tabId: String, title: String)

    @Query("UPDATE tab_state SET themeColor = :themeColor WHERE tabId = :tabId")
    suspend fun updateThemeColor(tabId: String, themeColor: Int?)

    @Query("UPDATE tab_state SET sortOrder = :sortOrder WHERE tabId = :tabId")
    suspend fun updateSortOrder(tabId: String, sortOrder: Int)

    /** 指定したタブを選択中にし、他は未選択にする */
    @Query("UPDATE tab_state SET isSelected = CASE WHEN tabId = :tabId THEN 1 ELSE 0 END")
    suspend fun setSelectedTab(tabId: String)

    @Query("UPDATE tab_state SET isSelected = 0")
    suspend fun clearSelectedTab()

    // --- 旧バージョンで tab_state.sessionState に保存されたデータをファイルへコピーするための補助クエリ ---
    // 移行が正しく動くと確認できるまでは DB 列のデータは消さず、バックアップとして残す。

    /** sessionState の文字数を取得する。セルの中身は CursorWindow に載らないため安全 */
    @Query("SELECT length(sessionState) FROM tab_state WHERE tabId = :tabId")
    suspend fun getSessionStateLength(tabId: String): Int?

    /**
     * sessionState を substr で部分取得する（1始まり）。
     * 巨大セルでも 1 チャンクずつなら CursorWindow 上限に収まるため、分割して読み出せる。
     */
    @Query("SELECT substr(sessionState, :start, :count) FROM tab_state WHERE tabId = :tabId")
    suspend fun getSessionStateChunk(tabId: String, start: Int, count: Int): String?
}

/**
 * tab_state からセッション状態(sessionState)を除いたメタデータ射影。
 * sessionState は肥大化して CursorWindow 上限を超え得るため一覧取得には含めない。
 */
data class TabStateRow(
    val tabId: String,
    val url: String,
    val title: String,
    val openerTabId: String,
    val themeColor: Int?,
    val sortOrder: Int,
    val isSelected: Int,
    val groupId: String,
)

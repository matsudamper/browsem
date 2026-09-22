package net.matsudamper.browser.data.tab

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProfile(profile: ProfileEntity)

    @Query("SELECT * FROM profile ORDER BY sortOrder ASC")
    abstract fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile ORDER BY sortOrder ASC")
    abstract suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("UPDATE profile SET name = :name WHERE profileId = :profileId")
    abstract suspend fun updateName(profileId: String, name: String)

    @Query("UPDATE profile SET iconKey = :iconKey WHERE profileId = :profileId")
    abstract suspend fun updateIconKey(profileId: String, iconKey: String)

    @Query("UPDATE profile SET isActive = CASE WHEN profileId = :profileId THEN 1 ELSE 0 END")
    abstract suspend fun setActiveProfile(profileId: String)

    @Query("UPDATE tab_group SET profileId = :profileId WHERE profileId = ''")
    abstract suspend fun assignOrphanGroupsToProfile(profileId: String)

    @Query("UPDATE tab_state SET profileId = :profileId WHERE profileId = ''")
    abstract suspend fun assignOrphanTabsToProfile(profileId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertGroupIfNotExists(group: TabGroupEntity)

    @Query("SELECT tabId FROM tab_state WHERE profileId = :profileId")
    abstract suspend fun getTabIds(profileId: String): List<String>

    /** プロファイルごとのタブ数。グループ先行割り当てのプレースホルダ行（url が空）は数えない */
    @Query("SELECT profileId, count(*) AS tabCount FROM tab_state WHERE url != '' GROUP BY profileId")
    abstract fun observeTabCounts(): Flow<List<ProfileTabCount>>

    @Query("DELETE FROM tab_group WHERE profileId = :profileId")
    abstract suspend fun deleteGroupsOfProfile(profileId: String)

    @Query("DELETE FROM tab_state WHERE profileId = :profileId")
    abstract suspend fun deleteTabsOfProfile(profileId: String)

    @Query("DELETE FROM profile WHERE profileId = :profileId")
    abstract suspend fun deleteProfile(profileId: String)

    /** プロファイルと、それに属するグループ・残っているタブ行をまとめて消す */
    @Transaction
    open suspend fun deleteProfileWithContents(profileId: String) {
        deleteGroupsOfProfile(profileId)
        deleteTabsOfProfile(profileId)
        deleteProfile(profileId)
    }

    /**
     * プロファイルが 1 件も無いときだけデフォルトプロファイルを作成し、
     * プロファイル未所属のグループとタブをそこへ寄せる。
     * プロファイル導入前のデータをそのまま引き継ぐための初期化で、起動のたびに呼んでよい。
     */
    @Transaction
    open suspend fun createDefaultProfileIfEmpty(defaultProfile: ProfileEntity) {
        if (getAllProfiles().isEmpty()) {
            upsertProfile(defaultProfile)
        }
        assignOrphanGroupsToProfile(defaultProfile.profileId)
        assignOrphanTabsToProfile(defaultProfile.profileId)
    }

    /** プロファイルと、そのプロファイル最初のタブグループをまとめて作成する */
    @Transaction
    open suspend fun insertProfileWithInitialGroup(profile: ProfileEntity, initialGroup: TabGroupEntity) {
        upsertProfile(profile)
        insertGroupIfNotExists(initialGroup)
    }
}

data class ProfileTabCount(val profileId: String, val tabCount: Int)

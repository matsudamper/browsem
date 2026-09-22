package net.matsudamper.browser.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.matsudamper.browser.data.history.BrowserDatabase
import net.matsudamper.browser.data.tab.ProfileEntity
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.TabGroupEntity

interface ProfileRepository {
    /** sortOrder 順の全プロファイル。デフォルトプロファイル作成前は空 */
    fun observeProfiles(): Flow<List<ProfileData>>

    /** プロファイル ID ごとのタブ数。タブが無いプロファイルは含まれない */
    fun observeTabCounts(): Flow<Map<ProfileId, Int>>

    /**
     * デフォルトプロファイルが無ければ作成し、プロファイル未所属のグループ・タブを所属させる。
     * 起動時に呼ぶ。
     */
    suspend fun createDefaultProfileIfEmpty()

    /** プロファイルと初期タブグループを作成して ID を返す */
    suspend fun addProfile(name: String, icon: ProfileIcon, sortOrder: Int): ProfileId

    suspend fun renameProfile(profileId: ProfileId, name: String)

    suspend fun updateProfileIcon(profileId: ProfileId, icon: ProfileIcon)

    suspend fun setActiveProfile(profileId: ProfileId)

    /** プロファイルに属するタブ ID。削除前にタブを閉じるために使う */
    suspend fun getTabIds(profileId: ProfileId): List<String>

    /** プロファイルと、それに属するグループ・タブ行・閲覧履歴を削除する。デフォルトプロファイルは消せない */
    suspend fun deleteProfile(profileId: ProfileId)
}

class ProfileRepositoryImpl(context: Context) : ProfileRepository {
    private val dao = TabDatabase.getInstance(context).profileDao()
    private val historyDao = BrowserDatabase.getInstance(context).historyDao()

    override fun observeProfiles(): Flow<List<ProfileData>> {
        return dao.observeProfiles().map { entities -> entities.map { it.toProfileData() } }
    }

    override fun observeTabCounts(): Flow<Map<ProfileId, Int>> {
        return dao.observeTabCounts().map { counts ->
            counts.associate { ProfileId(it.profileId) to it.tabCount }
        }
    }

    override suspend fun createDefaultProfileIfEmpty() {
        dao.createDefaultProfileIfEmpty(
            ProfileEntity(
                profileId = ProfileId.DEFAULT.value,
                name = DEFAULT_PROFILE_NAME,
                iconKey = ProfileIcon.PERSON.name,
                sortOrder = 0,
                isActive = true,
            ),
        )
    }

    override suspend fun addProfile(name: String, icon: ProfileIcon, sortOrder: Int): ProfileId {
        val id = ProfileId.generate()
        dao.insertProfileWithInitialGroup(
            profile = ProfileEntity(
                profileId = id.value,
                name = name,
                iconKey = icon.name,
                sortOrder = sortOrder,
                isActive = false,
            ),
            initialGroup = TabGroupEntity(
                groupId = TabGroupId.generate().value,
                name = DEFAULT_GROUP_NAME,
                sortOrder = 0,
                profileId = id.value,
            ),
        )
        return id
    }

    override suspend fun renameProfile(profileId: ProfileId, name: String) {
        dao.updateName(profileId.value, name)
    }

    override suspend fun updateProfileIcon(profileId: ProfileId, icon: ProfileIcon) {
        dao.updateIconKey(profileId.value, icon.name)
    }

    override suspend fun setActiveProfile(profileId: ProfileId) {
        dao.setActiveProfile(profileId.value)
    }

    override suspend fun getTabIds(profileId: ProfileId): List<String> {
        return dao.getTabIds(profileId.value)
    }

    override suspend fun deleteProfile(profileId: ProfileId) {
        require(profileId != ProfileId.DEFAULT) { "デフォルトプロファイルは削除できない" }
        dao.deleteProfileWithContents(profileId.value)
        historyDao.deleteAllOfProfile(profileId.value)
    }

    private fun ProfileEntity.toProfileData(): ProfileData {
        return ProfileData(
            id = ProfileId(profileId),
            name = name,
            icon = ProfileIcon.fromKeyOrDefault(iconKey),
            isActive = isActive,
        )
    }

    companion object {
        private const val DEFAULT_PROFILE_NAME = "デフォルト"
        private const val DEFAULT_GROUP_NAME = "デフォルト"
    }
}

data class ProfileData(
    val id: ProfileId,
    val name: String,
    val icon: ProfileIcon,
    val isActive: Boolean,
)

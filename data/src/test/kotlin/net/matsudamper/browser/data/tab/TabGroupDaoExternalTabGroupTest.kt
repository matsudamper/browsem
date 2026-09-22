package net.matsudamper.browser.data.tab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 外部アプリから開いたタブの所属先グループの選び方を検証する。
 *
 * デフォルトグループが無いプロファイルでも、先頭のグループへ割り当てられることを確認する。
 */
@RunWith(RobolectricTestRunner::class)
class TabGroupDaoExternalTabGroupTest {

    private lateinit var database: TabDatabase
    private lateinit var dao: TabGroupDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TabDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.tabGroupDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `デフォルトグループがあればそれを返す`() = runBlocking {
        dao.upsertGroup(group(groupId = "g1", profileId = PROFILE, sortOrder = 0, isDefault = false))
        dao.upsertGroup(group(groupId = "g2", profileId = PROFILE, sortOrder = 1, isDefault = true))

        assertEquals("g2", dao.getDefaultOrFirstGroupId(PROFILE))
    }

    @Test
    fun `デフォルトグループが無ければ並び順が先頭のグループを返す`() = runBlocking {
        dao.upsertGroup(group(groupId = "g1", profileId = PROFILE, sortOrder = 5, isDefault = false))
        dao.upsertGroup(group(groupId = "g2", profileId = PROFILE, sortOrder = 2, isDefault = false))

        assertEquals("g2", dao.getDefaultOrFirstGroupId(PROFILE))
    }

    @Test
    fun `他プロファイルのグループは候補にしない`() = runBlocking {
        dao.upsertGroup(group(groupId = "other", profileId = "other-profile", sortOrder = 0, isDefault = true))
        dao.upsertGroup(group(groupId = "g1", profileId = PROFILE, sortOrder = 0, isDefault = false))

        assertEquals("g1", dao.getDefaultOrFirstGroupId(PROFILE))
    }

    @Test
    fun `グループが無いプロファイルでは null`() = runBlocking {
        dao.upsertGroup(group(groupId = "other", profileId = "other-profile", sortOrder = 0, isDefault = true))

        assertNull(dao.getDefaultOrFirstGroupId(PROFILE))
    }

    private fun group(
        groupId: String,
        profileId: String,
        sortOrder: Int,
        isDefault: Boolean,
    ): TabGroupEntity {
        return TabGroupEntity(
            groupId = groupId,
            name = groupId,
            sortOrder = sortOrder,
            isDefault = isDefault,
            profileId = profileId,
        )
    }

    private companion object {
        const val PROFILE = "profile-a"
    }
}

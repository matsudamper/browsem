package net.matsudamper.browser.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.matsudamper.browser.data.tab.TabDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: ProfileRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TabDatabase.closeInstance()
        context.deleteDatabase("tab.db")
        repository = ProfileRepositoryImpl(context)
    }

    @After
    fun tearDown() {
        TabDatabase.closeInstance()
        context.deleteDatabase("tab.db")
    }

    @Test
    fun addProfileCreatesDefaultGroupForEachProfile() = runBlocking {
        val firstProfileId = repository.addProfile("仕事", ProfileIcon.PERSON, 0)
        val secondProfileId = repository.addProfile("個人", ProfileIcon.PERSON, 1)
        val groupDao = TabDatabase.getInstance(context).tabGroupDao()

        val firstGroup = groupDao.observeGroupsForProfile(firstProfileId.value).first().single()
        val secondGroup = groupDao.observeGroupsForProfile(secondProfileId.value).first().single()

        assertTrue(firstGroup.isDefault)
        assertTrue(secondGroup.isDefault)
        assertEquals(firstGroup.groupId, groupDao.getDefaultGroupId(firstProfileId.value))
        assertEquals(secondGroup.groupId, groupDao.getDefaultGroupId(secondProfileId.value))
    }
}

package net.matsudamper.browser.data.crashlog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashLogRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: CrashLogRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashLogDatabase.closeInstance()
        repository = CrashLogRepository(context)
    }

    @After
    fun tearDown() {
        runBlocking { repository.deleteAll() }
        CrashLogDatabase.closeInstance()
    }

    @Test
    fun saveCrashSync_persistsTitleAndBody() {
        val thread = Thread.currentThread()
        val throwable = RuntimeException("repository test")

        repository.saveCrashSync(thread, throwable)

        val saved = runBlocking { repository.getById(1) }
        assertNotNull(saved)
        assertEquals(CrashLogFormatter.extractTitle(throwable), saved?.title)
        assertEquals(CrashLogFormatter.formatBody(thread, throwable), saved?.body)
    }
}

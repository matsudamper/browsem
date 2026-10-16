package net.matsudamper.browser

import android.app.Application
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import net.matsudamper.browser.di.BrowserAppModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import net.matsudamper.browser.di.module as appModule

class DownloadWorkerFactoryTest {
    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `WorkManagerのクラス名検索でDownloadWorkerを生成できる`() {
        val context = mockk<Application>(relaxed = true)
        val initializer = mockk<GeckoRuntimeInitializer>()
        startKoin {
            androidContext(context)
            modules(BrowserAppModule().appModule(), module { single { initializer } })
        }
        val parameters = mockk<WorkerParameters>()
        val workId = UUID.randomUUID()
        every { parameters.id } returns workId

        val worker = KoinWorkerFactory().createWorker(context, DownloadWorker::class.java.name, parameters)

        assertNotNull("実際のアプリのDI定義からWorkerを取得できる", worker)
        assertTrue(worker is DownloadWorker)
        assertEquals(workId, worker?.id)
    }
}

package net.matsudamper.browser

import android.app.Application
import java.io.File
import net.matsudamper.browser.di.appModule
import net.matsudamper.browser.di.dataModule
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        cleanFilePromptsCache()
        startKoin {
            androidContext(this@BrowserApplication)
            workManagerFactory()
            modules(dataModule, appModule)
        }
    }

    private fun cleanFilePromptsCache() {
        File(cacheDir, "file_prompts").deleteRecursively()
    }
}

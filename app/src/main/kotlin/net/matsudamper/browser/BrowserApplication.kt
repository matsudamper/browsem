package net.matsudamper.browser

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.matsudamper.browser.di.appModule
import net.matsudamper.browser.di.dataModule
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class BrowserApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob())

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
        val dir = File(cacheDir, "file_prompts")
        val deleteFiles = dir.getChildrenRecursively()
        applicationScope.launch(Dispatchers.IO) {
            deleteFiles.forEach { it.delete() }
        }
    }

    private fun File.getChildrenRecursively(): List<File> {
        val children = listFiles()?.toList() ?: emptyList()
        return children + children.flatMap { it.getChildrenRecursively() }
    }
}

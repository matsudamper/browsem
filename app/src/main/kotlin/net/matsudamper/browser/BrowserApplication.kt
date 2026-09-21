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
        CrashLogExceptionHandler.install(this)
        cleanFilePromptsCache()
        startKoin {
            androidContext(this@BrowserApplication)
            workManagerFactory()
            modules(dataModule, appModule)
        }
        MainThreadWatchdog().start()
    }

    private fun cleanFilePromptsCache() {
        val dir = filePromptsCacheDir
        // 起動後のファイル選択で作られたファイルを消さないよう、削除対象は起動時点より古いものに限る
        val startedAt = System.currentTimeMillis()
        applicationScope.launch(Dispatchers.IO) {
            dir.getChildrenRecursively()
                .filter { it.lastModified() <= startedAt }
                .forEach { it.delete() }
        }
    }

    private fun File.getChildrenRecursively(): List<File> {
        val children = listFiles()?.toList() ?: listOf()
        return children + children.flatMap { it.getChildrenRecursively() }
    }
}

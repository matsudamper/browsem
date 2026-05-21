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
import org.koin.core.context.GlobalContext
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
        // 診断用拡張機能を起動時に強制インスタンス化してインストールさせる。
        // どこからも inject されないため明示的に get() しないとロードされない。
        GlobalContext.get().get<WebRequestProbeExtension>()
    }

    private fun cleanFilePromptsCache() {
        val dir = filePromptsCacheDir
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

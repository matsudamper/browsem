package net.matsudamper.browser

import android.app.Application
import net.matsudamper.browser.di.appModule
import net.matsudamper.browser.di.dataModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@BrowserApplication)
            modules(dataModule, appModule)
        }
    }
}

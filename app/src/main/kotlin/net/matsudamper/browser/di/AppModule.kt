package net.matsudamper.browser.di

import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.CastWebExtension
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.ReadabilityWebExtension
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.cast.CastManager
import net.matsudamper.browser.media.MediaWebExtension
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

val dataModule = module {
    single { SettingsRepository(androidContext()) }
    single { TabRepository(androidContext()) }
    single<TabGroupRepository> { TabGroupRepositoryImpl(androidContext()) }
    single { HistoryRepository(androidContext()) }
    single { DownloadRepository(androidContext()) }
    single<WebSuggestionRepository> { HttpWebSuggestionRepository() }
}

val appModule = module {
    single<GeckoRuntime> {
        GeckoRuntime.create(
            androidContext(),
            GeckoRuntimeSettings.Builder()
                .forceUserScalableEnabled(true)
                .build()
        )
    }
    // 拡張機能はプロセスに1つの GeckoRuntime に対してインストールするため single で管理
    single { ThemeColorWebExtension().also { it.install(get()) } }
    single { MediaWebExtension(androidContext()).also { it.install(get()) } }
    single { ReadabilityWebExtension().also { it.install(get()) } }
    single { FindInPageWebExtension().also { it.install(get()) } }
    single { CastWebExtension().also { it.install(get()) } }
    single { CastManager(androidContext()) }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

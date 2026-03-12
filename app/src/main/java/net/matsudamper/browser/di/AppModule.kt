package net.matsudamper.browser.di

import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import org.mozilla.geckoview.GeckoRuntime

val dataModule = module {
    single { SettingsRepository(androidContext()) }
    single { TabRepository(androidContext()) }
    single { HistoryRepository(androidContext()) }
    single<WebSuggestionRepository> { HttpWebSuggestionRepository() }
}

val appModule = module {
    single { GeckoRuntime.getDefault(androidContext()) }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(androidContext(), get(), get(), get(), get()) }
}

package net.matsudamper.browser.di

import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.MockLocationWebExtension
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.ViewportScaleWebExtension
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

val dataModule = module {
    single { BackupRepository(androidContext()) }
    single { SettingsRepository(androidContext()) }
    single { SiteSettingsRepository(androidContext()) }
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
                // ユーザーインストール拡張機能のバックグラウンドスクリプトを専用プロセスで実行し、
                // webRequest.onBeforeRequest 等によるリクエストのブロッキングを有効にする。
                // この設定がないと AdGuard などのコンテンツブロッカーが機能しない。
                .extensionsProcessEnabled(true)
                .build()
        )
    }
    // 拡張機能はプロセスに1つの GeckoRuntime に対してインストールするため single で管理
    single { ThemeColorWebExtension().also { it.install(get()) } }
    single { MediaWebExtension(androidContext()).also { it.install(get()) } }
    single { FindInPageWebExtension().also { it.install(get()) } }
    single { MockLocationWebExtension().also { it.install(get()) } }
    single { ViewportScaleWebExtension().also { it.install(get()) } }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

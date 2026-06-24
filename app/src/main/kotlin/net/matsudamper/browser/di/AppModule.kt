package net.matsudamper.browser.di

import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.MockLocationWebExtension
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.ViewportScaleWebExtension
import net.matsudamper.browser.AutocompleteStorageDelegate
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    single { AddressRepository(androidContext()) }
    single<WebSuggestionRepository> { HttpWebSuggestionRepository() }
}

val appModule = module {
    single<GeckoRuntime> {
        val context = androidContext()
        // GeckoView の住所フォーム自動入力を有効にするための設定ファイルを準備する。
        // GeckoRuntimeSettings.Builder には loginAutofillEnabled() はあるが
        // 住所用の公開 API がないため、configFilePath 経由で Gecko 内部プリファレンスを設定する。
        val geckoConfigFile = File(context.filesDir, "geckoview-config.yaml")
        context.assets.open("geckoview-config.yaml").use { input ->
            geckoConfigFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val runtime = GeckoRuntime.create(
            context,
            GeckoRuntimeSettings.Builder()
                .configFilePath(geckoConfigFile.absolutePath)
                .forceUserScalableEnabled(true)
                // ユーザーインストール拡張機能のバックグラウンドスクリプトを専用プロセスで実行し、
                // webRequest.onBeforeRequest 等によるリクエストのブロッキングを有効にする。
                // この設定がないと AdGuard などのコンテンツブロッカーが機能しない。
                .extensionsProcessEnabled(true)
                .build()
        )
        val storageDelegate = AutocompleteStorageDelegate(
            addressRepository = get(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
        runtime.autocompleteStorageDelegate = storageDelegate
        runtime
    }
    // 拡張機能はプロセスに1つの GeckoRuntime に対してインストールするため single で管理
    single { ThemeColorWebExtension().also { it.install(get()) } }
    single { MediaWebExtension(androidContext()).also { it.install(get()) } }
    single { FindInPageWebExtension().also { it.install(get()) } }
    single { MockLocationWebExtension().also { it.install(get()) } }
    single { ViewportScaleWebExtension().also { it.install(get()) } }
    // eTLD+1 (基底ドメイン) の算出に使用する Public Suffix List。初回ロードを共有するため single
    single { PublicSuffixList(androidContext()) }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

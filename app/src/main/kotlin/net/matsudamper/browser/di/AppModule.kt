package net.matsudamper.browser.di

import android.util.Log
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.allowUnsignedExtensions
import net.matsudamper.browser.DevToolsWebExtension
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.FindInPageWebExtension
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.MockLocationWebExtension
import net.matsudamper.browser.NetworkLogStore
import net.matsudamper.browser.NetworkLogWebExtension
import net.matsudamper.browser.ThemeColorWebExtension
import net.matsudamper.browser.TwitterShareWebExtension
import net.matsudamper.browser.ViewportScaleWebExtension
import net.matsudamper.browser.WebExtensionActionController
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedExtensionsProcessEnabled
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.media.MediaWebExtension
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        val settings = get<SettingsRepository>()
        val extensionsProcessEnabled = runBlocking {
            settings.settings.first().resolvedExtensionsProcessEnabled()
        }
        GeckoRuntime.create(
            androidContext(),
            GeckoRuntimeSettings.Builder()
                .forceUserScalableEnabled(true)
                .extensionsProcessEnabled(extensionsProcessEnabled)
                .build()
        ).also {
            // 署名要求は GeckoRuntimeSettings では設定できず pref でしか制御できない。
            // 起動時の検証にも使われる値のため runtime 生成のたびに反映する。
            // Gecko 起動前の呼び出しはキューされる。
            allowUnsignedExtensions().accept({}, { error ->
                Log.w("AppModule", "署名要求 pref の反映に失敗", error)
            })
        }
    }
    // 拡張機能はプロセスに1つの GeckoRuntime に対してインストールするため single で管理
    single { ThemeColorWebExtension().also { it.install(get()) } }
    single { MediaWebExtension(androidContext()).also { it.install(get()) } }
    single { FindInPageWebExtension().also { it.install(get()) } }
    single { DevToolsWebExtension().also { it.install(get()) } }
    single { MockLocationWebExtension().also { it.install(get()) } }
    // 通信ログはランタイム単位で収集するため、ストア・拡張機能ともに single で管理
    single { NetworkLogStore() }
    single { NetworkLogWebExtension(get()).also { it.install(get()) } }
    single { ViewportScaleWebExtension().also { it.install(get()) } }
    single { TwitterShareWebExtension().also { it.install(get()) } }
    // 拡張機能のツールバーアクションはランタイム単位で受け取るため single
    single { WebExtensionActionController(get()) }
    // eTLD+1 (基底ドメイン) の算出に使用する Public Suffix List。初回ロードを共有するため single
    single { PublicSuffixList(androidContext()) }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

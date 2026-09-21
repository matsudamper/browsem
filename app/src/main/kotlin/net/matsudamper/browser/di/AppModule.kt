package net.matsudamper.browser.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.ExtensionRuntimeCoordinator
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.GeckoRuntimeInitializer
import net.matsudamper.browser.WebExtensionActionController
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.feature.addressautofill.AddressAutofillCoordinator
import net.matsudamper.browser.feature.addressautofill.AddressAutofillWebExtension
import net.matsudamper.browser.feature.devtools.DevToolsWebExtension
import net.matsudamper.browser.feature.findinpage.FindInPageWebExtension
import net.matsudamper.browser.feature.forminputautofill.FormInputAutofillCoordinator
import net.matsudamper.browser.feature.forminputautofill.FormInputAutofillWebExtension
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.feature.mocklocation.MockLocationWebExtension
import net.matsudamper.browser.feature.networklog.NetworkLogStore
import net.matsudamper.browser.feature.networklog.NetworkLogWebExtension
import net.matsudamper.browser.feature.themecolor.ThemeColorWebExtension
import net.matsudamper.browser.feature.twittershare.TwitterShareWebExtension
import net.matsudamper.browser.feature.viewportscale.ViewportScaleWebExtension
import net.matsudamper.browser.feature.webauthncompat.WebAuthnCompatWebExtension
import net.matsudamper.browser.feature.websharefiles.WebShareFilesWebExtension
import net.matsudamper.browser.screen.settings.WebAuthnSettingsUpdateQueue
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mozilla.geckoview.GeckoRuntime

val dataModule = module {
    single { BackupRepository(androidContext()) }
    single { SettingsRepository(androidContext()) }
    single { SiteSettingsRepository(androidContext()) }
    single { TabRepository(androidContext()) }
    single<TabGroupRepository> { TabGroupRepositoryImpl(androidContext()) }
    single { HistoryRepository(androidContext()) }
    single { DownloadRepository(androidContext()) }
    single { AddressRepository(androidContext()) }
    single { FormInputRepository(androidContext()) }
    single { CrashLogRepository(androidContext()) }
    single<WebSuggestionRepository> { HttpWebSuggestionRepository() }
}

val appModule = module {
    // プロセスと同じ寿命で動く CoroutineScope。GeckoRuntime に紐づく delegate など、
    // 画面より長く生きる処理から共有する。
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single { AddressAutofillWebExtension() }
    single { FormInputAutofillWebExtension() }
    single { WebAuthnCompatWebExtension() }
    single { WebAuthnSettingsUpdateQueue(applicationScope = get()) }
    single { PageTranslationWebExtension(get()) }
    single { AddressAutofillCoordinator(get()) }
    factory { FormInputAutofillCoordinator(get()) }
    single {
        GeckoRuntimeInitializer(
            context = androidContext(),
            settingsRepository = get(),
            addressRepository = get(),
            addressAutofillWebExtension = get(),
            formInputAutofillWebExtension = get(),
            pageTranslationWebExtension = get(),
            webAuthnCompatWebExtension = get(),
            addressAutofillCoordinator = get(),
            autocompleteCoroutineScope = get(),
        )
    }
    // 初期化は GeckoRuntimeInitializer が行う。ここでは初期化済みのインスタンスを配るだけにする。
    single<GeckoRuntime> { get<GeckoRuntimeInitializer>().requireInitialized() }
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
    single { WebShareFilesWebExtension().also { it.install(get()) } }
    // 拡張機能のツールバーアクションはランタイム単位で受け取るため single
    single { WebExtensionActionController(get()) }
    single { ExtensionRuntimeCoordinator(get()) }
    // eTLD+1 (基底ドメイン) の算出に使用する Public Suffix List。初回ロードを共有するため single
    single { PublicSuffixList(androidContext()) }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

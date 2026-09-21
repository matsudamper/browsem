package net.matsudamper.browser.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.CustomTabsWarmupStore
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.ExtensionRuntimeCoordinator
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.GeckoRuntimeInitializer
import net.matsudamper.browser.WebAppBrowserViewModel
import net.matsudamper.browser.WebExtensionActionController
import net.matsudamper.browser.core.TabStore
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.ProfileRepositoryImpl
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.data.crashlog.CrashLogRepository
import net.matsudamper.browser.data.download.DownloadRepository
import net.matsudamper.browser.data.forminput.FormInputOrigin
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
import net.matsudamper.browser.screen.addresses.AddressEditScreenViewModel
import net.matsudamper.browser.screen.addresses.AddressesScreenViewModel
import net.matsudamper.browser.screen.backup.BackupProgressViewModel
import net.matsudamper.browser.screen.browser.CustomTabScreenViewModel
import net.matsudamper.browser.screen.browser.WebAppScreenViewModel
import net.matsudamper.browser.screen.crashlog.CrashLogDetailScreenViewModel
import net.matsudamper.browser.screen.crashlog.CrashLogsScreenViewModel
import net.matsudamper.browser.screen.downloads.DownloadManagementScreenViewModel
import net.matsudamper.browser.screen.extensions.ExtensionsScreenViewModel
import net.matsudamper.browser.screen.history.HistoryScreenViewModel
import net.matsudamper.browser.screen.settings.SettingsScreenViewModel
import net.matsudamper.browser.screen.settings.WebAuthnSettingsUpdateQueue
import net.matsudamper.browser.screen.siteforminput.SiteFormInputFieldScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputPathScreenViewModel
import net.matsudamper.browser.screen.siteforminput.SiteFormInputPathsScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsListScreenViewModel
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenParams
import net.matsudamper.browser.screen.sitesettings.SiteSettingsScreenViewModel
import net.matsudamper.browser.screen.tab.TabsScreenViewModel
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.koin.android.ext.koin.androidApplication
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
    single<ProfileRepository> { ProfileRepositoryImpl(androidContext()) }
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
    // カスタムタブのプリウォームはプロセス生存中いつでも来るため single で持つ。
    single { CustomTabsWarmupStore(geckoRuntimeInitializer = get(), webAuthnCompatWebExtension = get()) }
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
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // 画面の ViewModel は生成を Koin に集約し、画面側は koinViewModel() で解決する
    viewModel {
        SettingsScreenViewModel(
            settingsRepository = get(),
            runtime = get(),
            webAuthnCompatWebExtension = get(),
            webAuthnSettingsUpdateQueue = get(),
        )
    }
    viewModel { SiteSettingsListScreenViewModel(get()) }
    viewModel { (params: SiteSettingsScreenParams) ->
        SiteSettingsScreenViewModel(
            host = params.host,
            formInputOrigin = params.formInputOrigin,
            siteSettingsRepository = get(),
            formInputRepository = get(),
            geckoRuntime = get(),
            publicSuffixList = get(),
            securityInfo = params.securityInfo,
        )
    }
    viewModel { (origin: FormInputOrigin) ->
        SiteFormInputPathsScreenViewModel(origin = origin, formInputRepository = get())
    }
    viewModel { (origin: FormInputOrigin, path: String) ->
        SiteFormInputPathScreenViewModel(origin = origin, path = path, formInputRepository = get())
    }
    viewModel { (origin: FormInputOrigin, path: String, fieldKey: String) ->
        SiteFormInputFieldScreenViewModel(
            origin = origin,
            path = path,
            fieldKey = fieldKey,
            formInputRepository = get(),
        )
    }
    viewModel { HistoryScreenViewModel(get()) }
    viewModel { AddressesScreenViewModel(get()) }
    viewModel { (addressId: Long) ->
        AddressEditScreenViewModel(addressRepository = get(), addressId = addressId)
    }
    viewModel { CrashLogsScreenViewModel(get()) }
    viewModel { (crashLogId: Long) ->
        CrashLogDetailScreenViewModel(crashLogRepository = get(), crashLogId = crashLogId)
    }
    viewModel {
        ExtensionsScreenViewModel(
            application = androidApplication(),
            runtime = get(),
            settingsRepository = get(),
            extensionRuntimeCoordinator = get(),
        )
    }
    viewModel { DownloadManagementScreenViewModel(androidApplication()) }
    viewModel { (isImport: Boolean) -> BackupProgressViewModel(isImport, get()) }
    viewModel { (tabStore: TabStore) ->
        TabsScreenViewModel(
            tabStore = tabStore,
            tabGroupRepository = get(),
            profileRepository = get(),
            playingTabIds = get<MediaWebExtension>().playingTabIds,
        )
    }
    viewModel { CustomTabScreenViewModel(get(), get(), get()) }
    viewModel { WebAppBrowserViewModel(get(), get(), get(), get()) }
    viewModel { WebAppScreenViewModel(get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

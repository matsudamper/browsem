package net.matsudamper.browser.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.BackgroundSessionSuspender
import net.matsudamper.browser.BrowserTabController
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.CustomTabProfileDeletionNotifier
import net.matsudamper.browser.CustomTabsWarmupStore
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.ExtensionRuntimeCoordinator
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.GeckoRuntimeInitializer
import net.matsudamper.browser.WebAppBrowserViewModel
import net.matsudamper.browser.WebAppLaunchRequest
import net.matsudamper.browser.WebAppShortcutManager
import net.matsudamper.browser.WebExtensionActionController
import net.matsudamper.browser.data.BackupRepository
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.ProfileRepositoryImpl
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabGroupRepositoryImpl
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.WebAppRepository
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
import net.matsudamper.browser.screen.extensions.ExtensionSettingsScreenViewModel
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
import net.matsudamper.browser.screen.webapp.WebAppsScreenViewModel
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.koin.android.annotation.KoinWorker
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Module
import org.koin.core.annotation.Qualifier
import org.koin.core.annotation.Single
import org.mozilla.geckoview.GeckoRuntime

@KoinApplication
class BrowserKoinApplication

/**
 * 定義関数はこのクラスと同じパッケージに置く。別パッケージへ移すと ComponentScan から外れる。
 */
@Module
@ComponentScan
@Configuration
class BrowserAppModule

@Single
internal fun backupRepository(context: Context): BackupRepository = BackupRepository(context)

@Single
internal fun settingsRepository(context: Context): SettingsRepository = SettingsRepository(context)

@Single
internal fun siteSettingsRepository(context: Context): SiteSettingsRepository = SiteSettingsRepository(context)

@Single
internal fun tabRepository(context: Context): TabRepository = TabRepository(context)

@Single
internal fun tabGroupRepository(context: Context): TabGroupRepository = TabGroupRepositoryImpl(context)

@Single
internal fun profileRepository(context: Context): ProfileRepository = ProfileRepositoryImpl(context)

@Single
internal fun historyRepository(context: Context): HistoryRepository = HistoryRepository(context)

@Single
internal fun downloadRepository(context: Context): DownloadRepository = DownloadRepository(context)

@Single
internal fun addressRepository(context: Context): AddressRepository = AddressRepository(context)

@Single
internal fun formInputRepository(context: Context): FormInputRepository = FormInputRepository(context)

@Single
internal fun crashLogRepository(context: Context): CrashLogRepository = CrashLogRepository(context)

@Single
internal fun webAppRepository(context: Context): WebAppRepository = WebAppRepository(context)

@Single
internal fun webSuggestionRepository(): WebSuggestionRepository = HttpWebSuggestionRepository()

// プロセスと同じ寿命で動く CoroutineScope。GeckoRuntime に紐づく delegate など、
// 画面より長く生きる処理から共有する。
@Single
internal fun applicationCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@Single
internal fun addressAutofillWebExtension(): AddressAutofillWebExtension = AddressAutofillWebExtension()

@Single
internal fun formInputAutofillWebExtension(): FormInputAutofillWebExtension = FormInputAutofillWebExtension()

@Single
internal fun webAuthnCompatWebExtension(): WebAuthnCompatWebExtension = WebAuthnCompatWebExtension()

@Single
internal fun webAuthnSettingsUpdateQueue(
    applicationCoroutineScope: CoroutineScope,
): WebAuthnSettingsUpdateQueue = WebAuthnSettingsUpdateQueue(applicationCoroutineScope)

@Single
internal fun pageTranslationWebExtension(
    crashLogRepository: CrashLogRepository,
): PageTranslationWebExtension = PageTranslationWebExtension(crashLogRepository)

@Single
internal fun addressAutofillCoordinator(
    addressAutofillWebExtension: AddressAutofillWebExtension,
): AddressAutofillCoordinator = AddressAutofillCoordinator(addressAutofillWebExtension)

@Factory
internal fun formInputAutofillCoordinator(
    formInputAutofillWebExtension: FormInputAutofillWebExtension,
): FormInputAutofillCoordinator = FormInputAutofillCoordinator(formInputAutofillWebExtension)

@Single
internal fun geckoRuntimeInitializer(
    context: Context,
    settingsRepository: SettingsRepository,
    addressRepository: AddressRepository,
    addressAutofillWebExtension: AddressAutofillWebExtension,
    formInputAutofillWebExtension: FormInputAutofillWebExtension,
    pageTranslationWebExtension: PageTranslationWebExtension,
    webAuthnCompatWebExtension: WebAuthnCompatWebExtension,
    addressAutofillCoordinator: AddressAutofillCoordinator,
    applicationCoroutineScope: CoroutineScope,
): GeckoRuntimeInitializer {
    return GeckoRuntimeInitializer(
        context = context,
        settingsRepository = settingsRepository,
        addressRepository = addressRepository,
        addressAutofillWebExtension = addressAutofillWebExtension,
        formInputAutofillWebExtension = formInputAutofillWebExtension,
        pageTranslationWebExtension = pageTranslationWebExtension,
        webAuthnCompatWebExtension = webAuthnCompatWebExtension,
        addressAutofillCoordinator = addressAutofillCoordinator,
        autocompleteCoroutineScope = applicationCoroutineScope,
    )
}

// 本体とカスタムタブは別 Activity のタブ管理を持つ。プロファイル削除を両方へ届ける。
@Single
internal fun customTabProfileDeletionNotifier(): CustomTabProfileDeletionNotifier {
    return CustomTabProfileDeletionNotifier()
}

// カスタムタブのプリウォームはプロセス生存中いつでも来るため single で持つ。
@Single
internal fun customTabsWarmupStore(
    geckoRuntimeInitializer: GeckoRuntimeInitializer,
    webAuthnCompatWebExtension: WebAuthnCompatWebExtension,
    profileRepository: ProfileRepository,
): CustomTabsWarmupStore {
    return CustomTabsWarmupStore(
        geckoRuntimeInitializer = geckoRuntimeInitializer,
        webAuthnCompatWebExtension = webAuthnCompatWebExtension,
        profileRepository = profileRepository,
    )
}

// 初期化は GeckoRuntimeInitializer が行う。ここでは初期化済みのインスタンスを配るだけにする。
@Single
internal fun geckoRuntime(geckoRuntimeInitializer: GeckoRuntimeInitializer): GeckoRuntime {
    return geckoRuntimeInitializer.requireInitialized()
}

// 拡張機能はプロセスに1つの GeckoRuntime に対してインストールするため single で管理する。
@Single
internal fun themeColorWebExtension(runtime: GeckoRuntime): ThemeColorWebExtension {
    return ThemeColorWebExtension().also { it.install(runtime) }
}

@Single
internal fun mediaWebExtension(
    context: Context,
    runtime: GeckoRuntime,
    crashLogRepository: CrashLogRepository,
): MediaWebExtension {
    return MediaWebExtension(
        context = context,
        onBlockedPlayback = { tag, title, body ->
            crashLogRepository.saveSync(tag = tag, title = title, body = body)
        },
    ).also { it.install(runtime) }
}

@Single
internal fun backgroundSessionSuspender(
    mediaWebExtension: MediaWebExtension,
): BackgroundSessionSuspender {
    return BackgroundSessionSuspender(mediaWebExtension).also { suspender ->
        mediaWebExtension.onSessionActivated = { session ->
            suspender.noteSessionActivated(session)
        }
        mediaWebExtension.addBackgroundPlaybackStoppedListener { session ->
            suspender.onPlaybackStopped(session)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(suspender)
    }
}

@Single
internal fun findInPageWebExtension(runtime: GeckoRuntime): FindInPageWebExtension {
    return FindInPageWebExtension().also { it.install(runtime) }
}

@Single
internal fun devToolsWebExtension(runtime: GeckoRuntime): DevToolsWebExtension {
    return DevToolsWebExtension().also { it.install(runtime) }
}

@Single
internal fun mockLocationWebExtension(runtime: GeckoRuntime): MockLocationWebExtension {
    return MockLocationWebExtension().also { it.install(runtime) }
}

// 通信ログはランタイム単位で収集するため、ストア・拡張機能ともに single で管理する。
@Single
internal fun networkLogStore(): NetworkLogStore = NetworkLogStore()

@Single
internal fun networkLogWebExtension(store: NetworkLogStore, runtime: GeckoRuntime): NetworkLogWebExtension {
    return NetworkLogWebExtension(store).also { it.install(runtime) }
}

@Single
internal fun viewportScaleWebExtension(runtime: GeckoRuntime): ViewportScaleWebExtension {
    return ViewportScaleWebExtension().also { it.install(runtime) }
}

@Single
internal fun twitterShareWebExtension(runtime: GeckoRuntime): TwitterShareWebExtension {
    return TwitterShareWebExtension().also { it.install(runtime) }
}

@Single
internal fun webShareFilesWebExtension(runtime: GeckoRuntime): WebShareFilesWebExtension {
    return WebShareFilesWebExtension().also { it.install(runtime) }
}

// 拡張機能のツールバーアクションはランタイム単位で受け取るため single
@Single
internal fun webExtensionActionController(runtime: GeckoRuntime): WebExtensionActionController {
    return WebExtensionActionController(runtime)
}

@Single
internal fun extensionRuntimeCoordinator(runtime: GeckoRuntime): ExtensionRuntimeCoordinator {
    return ExtensionRuntimeCoordinator(runtime)
}

// eTLD+1 (基底ドメイン) の算出に使用する Public Suffix List。初回ロードを共有するため single
@Single
internal fun publicSuffixList(context: Context): PublicSuffixList = PublicSuffixList(context)

@Single
internal fun webAppShortcutManager(context: Context): WebAppShortcutManager = WebAppShortcutManager(context)

@Factory
internal fun geckoDownloadManager(
    context: Context,
    downloadRepository: DownloadRepository,
): GeckoDownloadManager = GeckoDownloadManager(context, downloadRepository)

@KoinViewModel
internal fun browserViewModel(
    runtime: GeckoRuntime,
    themeColorExtension: ThemeColorWebExtension,
    mediaWebExtension: MediaWebExtension,
    settingsRepository: SettingsRepository,
    tabRepository: TabRepository,
    tabGroupRepository: TabGroupRepository,
    profileRepository: ProfileRepository,
    mockLocationWebExtension: MockLocationWebExtension,
    pageTranslationWebExtension: PageTranslationWebExtension,
    siteSettingsRepository: SiteSettingsRepository,
    applicationCoroutineScope: CoroutineScope,
    backgroundSessionSuspender: BackgroundSessionSuspender,
    customTabProfileDeletionNotifier: CustomTabProfileDeletionNotifier,
): BrowserViewModel {
    return BrowserViewModel(
        runtime = runtime,
        themeColorExtension = themeColorExtension,
        mediaWebExtension = mediaWebExtension,
        settingsRepository = settingsRepository,
        tabRepository = tabRepository,
        tabGroupRepository = tabGroupRepository,
        profileRepository = profileRepository,
        mockLocationWebExtension = mockLocationWebExtension,
        pageTranslationWebExtension = pageTranslationWebExtension,
        siteSettingsRepository = siteSettingsRepository,
        applicationScope = applicationCoroutineScope,
        backgroundSessionSuspender = backgroundSessionSuspender,
        customTabProfileDeletionNotifier = customTabProfileDeletionNotifier,
    )
}

@KoinViewModel
internal fun settingsScreenViewModel(
    settingsRepository: SettingsRepository,
    runtime: GeckoRuntime,
    webAuthnCompatWebExtension: WebAuthnCompatWebExtension,
    webAuthnSettingsUpdateQueue: WebAuthnSettingsUpdateQueue,
): SettingsScreenViewModel {
    return SettingsScreenViewModel(
        settingsRepository = settingsRepository,
        runtime = runtime,
        webAuthnCompatWebExtension = webAuthnCompatWebExtension,
        webAuthnSettingsUpdateQueue = webAuthnSettingsUpdateQueue,
    )
}

@KoinViewModel
internal fun siteSettingsListScreenViewModel(
    siteSettingsRepository: SiteSettingsRepository,
): SiteSettingsListScreenViewModel = SiteSettingsListScreenViewModel(siteSettingsRepository)

@KoinViewModel
internal fun siteSettingsScreenViewModel(
    @InjectedParam params: SiteSettingsScreenParams,
    siteSettingsRepository: SiteSettingsRepository,
    formInputRepository: FormInputRepository,
    geckoRuntime: GeckoRuntime,
    publicSuffixList: PublicSuffixList,
): SiteSettingsScreenViewModel {
    return SiteSettingsScreenViewModel(
        host = params.host,
        formInputOrigin = params.formInputOrigin,
        siteSettingsRepository = siteSettingsRepository,
        formInputRepository = formInputRepository,
        geckoRuntime = geckoRuntime,
        publicSuffixList = publicSuffixList,
        securityInfo = params.securityInfo,
    )
}

@KoinViewModel
internal fun siteFormInputPathsScreenViewModel(
    @InjectedParam origin: FormInputOrigin,
    formInputRepository: FormInputRepository,
): SiteFormInputPathsScreenViewModel {
    return SiteFormInputPathsScreenViewModel(
        origin = origin,
        formInputRepository = formInputRepository,
    )
}

@KoinViewModel
internal fun siteFormInputPathScreenViewModel(
    @InjectedParam origin: FormInputOrigin,
    @InjectedParam path: String,
    formInputRepository: FormInputRepository,
): SiteFormInputPathScreenViewModel {
    return SiteFormInputPathScreenViewModel(
        origin = origin,
        path = path,
        formInputRepository = formInputRepository,
    )
}

@KoinViewModel
internal fun siteFormInputFieldScreenViewModel(
    @InjectedParam origin: FormInputOrigin,
    @InjectedParam path: String,
    @InjectedParam fieldKey: String,
    formInputRepository: FormInputRepository,
): SiteFormInputFieldScreenViewModel {
    return SiteFormInputFieldScreenViewModel(
        origin = origin,
        path = path,
        fieldKey = fieldKey,
        formInputRepository = formInputRepository,
    )
}

@KoinViewModel
internal fun historyScreenViewModel(
    historyRepository: HistoryRepository,
    profileRepository: ProfileRepository,
): HistoryScreenViewModel = HistoryScreenViewModel(historyRepository, profileRepository)

@KoinViewModel
internal fun addressesScreenViewModel(addressRepository: AddressRepository): AddressesScreenViewModel {
    return AddressesScreenViewModel(addressRepository)
}

@KoinViewModel
internal fun addressEditScreenViewModel(
    @InjectedParam addressId: Long,
    addressRepository: AddressRepository,
): AddressEditScreenViewModel = AddressEditScreenViewModel(addressRepository, addressId)

@KoinViewModel
internal fun crashLogsScreenViewModel(crashLogRepository: CrashLogRepository): CrashLogsScreenViewModel {
    return CrashLogsScreenViewModel(crashLogRepository)
}

@KoinViewModel
internal fun webAppsScreenViewModel(
    webAppRepository: WebAppRepository,
    profileRepository: ProfileRepository,
    webAppShortcutManager: WebAppShortcutManager,
): WebAppsScreenViewModel {
    return WebAppsScreenViewModel(
        webAppRepository = webAppRepository,
        profileRepository = profileRepository,
        webAppShortcutManager = webAppShortcutManager,
    )
}

@KoinViewModel
internal fun crashLogDetailScreenViewModel(
    @InjectedParam crashLogId: Long,
    crashLogRepository: CrashLogRepository,
): CrashLogDetailScreenViewModel = CrashLogDetailScreenViewModel(crashLogRepository, crashLogId)

@KoinViewModel
internal fun extensionsScreenViewModel(
    application: Application,
    runtime: GeckoRuntime,
    settingsRepository: SettingsRepository,
    extensionRuntimeCoordinator: ExtensionRuntimeCoordinator,
): ExtensionsScreenViewModel {
    return ExtensionsScreenViewModel(
        application = application,
        runtime = runtime,
        settingsRepository = settingsRepository,
        extensionRuntimeCoordinator = extensionRuntimeCoordinator,
    )
}

@KoinViewModel
internal fun extensionSettingsScreenViewModel(
    @InjectedParam extensionName: String,
    @InjectedParam optionsPageUrl: String,
    runtime: GeckoRuntime,
): ExtensionSettingsScreenViewModel {
    return ExtensionSettingsScreenViewModel(
        extensionName = extensionName,
        optionsPageUrl = optionsPageUrl,
        runtime = runtime,
    )
}

@KoinViewModel
internal fun downloadManagementScreenViewModel(application: Application): DownloadManagementScreenViewModel {
    return DownloadManagementScreenViewModel(application)
}

@KoinViewModel
internal fun backupProgressViewModel(
    @InjectedParam isImport: Boolean,
    backupRepository: BackupRepository,
): BackupProgressViewModel = BackupProgressViewModel(isImport, backupRepository)

@KoinViewModel
internal fun tabsScreenViewModel(
    @InjectedParam tabStore: BrowserTabController,
    tabGroupRepository: TabGroupRepository,
    profileRepository: ProfileRepository,
    mediaWebExtension: MediaWebExtension,
): TabsScreenViewModel {
    return TabsScreenViewModel(
        tabStore = tabStore,
        tabGroupRepository = tabGroupRepository,
        profileRepository = profileRepository,
        playingTabIds = mediaWebExtension.playingTabIds,
    )
}

@KoinViewModel
internal fun customTabScreenViewModel(
    historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
    webSuggestionRepository: WebSuggestionRepository,
    webAppRepository: WebAppRepository,
    @InjectedParam displayedProfileId: Flow<ProfileId>,
): CustomTabScreenViewModel {
    return CustomTabScreenViewModel(
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
        webSuggestionRepository = webSuggestionRepository,
        webAppRepository = webAppRepository,
        displayedProfileId = displayedProfileId,
    )
}

@KoinViewModel
internal fun webAppBrowserViewModel(
    @InjectedParam launchRequest: WebAppLaunchRequest,
    tabRepository: TabRepository,
    webAppRepository: WebAppRepository,
    settingsRepository: SettingsRepository,
    profileRepository: ProfileRepository,
    webAppShortcutManager: WebAppShortcutManager,
    runtime: GeckoRuntime,
    mediaWebExtension: MediaWebExtension,
    pageTranslationWebExtension: PageTranslationWebExtension,
    applicationCoroutineScope: CoroutineScope,
    backgroundSessionSuspender: BackgroundSessionSuspender,
): WebAppBrowserViewModel {
    return WebAppBrowserViewModel(
        launchRequest = launchRequest,
        tabRepository = tabRepository,
        webAppRepository = webAppRepository,
        settingsRepository = settingsRepository,
        profileRepository = profileRepository,
        webAppShortcutManager = webAppShortcutManager,
        runtime = runtime,
        mediaWebExtension = mediaWebExtension,
        pageTranslationWebExtension = pageTranslationWebExtension,
        applicationScope = applicationCoroutineScope,
        backgroundSessionSuspender = backgroundSessionSuspender,
    )
}

@KoinViewModel
internal fun webAppScreenViewModel(
    historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
    webSuggestionRepository: WebSuggestionRepository,
    webAppRepository: WebAppRepository,
): WebAppScreenViewModel {
    return WebAppScreenViewModel(
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
        webSuggestionRepository = webSuggestionRepository,
        webAppRepository = webAppRepository,
    )
}

// Compiler Plugin 1.2.1 は関数の Worker 定義に検索用のクラス名を補わないため、明示する。
@KoinWorker
@Qualifier(DownloadWorker::class)
internal fun downloadWorker(
    context: Context,
    workerParameters: WorkerParameters,
    geckoRuntimeInitializer: GeckoRuntimeInitializer,
): DownloadWorker = DownloadWorker(context, workerParameters, geckoRuntimeInitializer)

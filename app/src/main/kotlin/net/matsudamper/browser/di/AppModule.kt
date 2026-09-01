package net.matsudamper.browser.di

import android.util.Log
import androidx.annotation.OptIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import net.matsudamper.browser.BrowserViewModel
import net.matsudamper.browser.DownloadWorker
import net.matsudamper.browser.ExtensionRuntimeCoordinator
import net.matsudamper.browser.GeckoDownloadManager
import net.matsudamper.browser.WebExtensionActionController
import net.matsudamper.browser.allowUnsignedExtensions
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
import net.matsudamper.browser.data.resolvedExtensionsProcessEnabled
import net.matsudamper.browser.data.resolvedInputAutoZoomEnabled
import net.matsudamper.browser.data.websuggestion.HttpWebSuggestionRepository
import net.matsudamper.browser.data.websuggestion.WebSuggestionRepository
import net.matsudamper.browser.feature.addressautofill.AddressAutofillCoordinator
import net.matsudamper.browser.feature.addressautofill.AddressAutofillWebExtension
import net.matsudamper.browser.feature.addressautofill.AutocompleteStorageDelegate
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
import net.matsudamper.browser.feature.websharefiles.WebShareFilesWebExtension
import net.matsudamper.browser.feature.viewportscale.ViewportScaleWebExtension
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
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
    single { FormInputRepository(androidContext()) }
    single { CrashLogRepository(androidContext()) }
    single<WebSuggestionRepository> { HttpWebSuggestionRepository() }
}

val appModule = module {
    single { AddressAutofillWebExtension() }
    single { FormInputAutofillWebExtension() }
    single { AddressAutofillCoordinator(get()) }
    factory { FormInputAutofillCoordinator(get()) }
    single<GeckoRuntime> {
        // Gecko 起動前の pref 設定はキューされる。メインスレッドをブロックして待機すると
        // initializeGeckoRuntime() とデッドロックするため非同期で投入する。
        enableAddressAutofill()
        val settings = get<SettingsRepository>()
        val browserSettings = runBlocking {
            settings.settings.first()
        }
        val extensionsProcessEnabled = browserSettings.resolvedExtensionsProcessEnabled()
        val inputAutoZoomEnabled = browserSettings.resolvedInputAutoZoomEnabled()
        GeckoRuntime.create(
            androidContext(),
            GeckoRuntimeSettings.Builder()
                .forceUserScalableEnabled(true)
                .inputAutoZoomEnabled(inputAutoZoomEnabled)
                .extensionsProcessEnabled(extensionsProcessEnabled)
                .build(),
        ).also {
            get<AddressAutofillWebExtension>().install(it)
            get<FormInputAutofillWebExtension>().install(it)
            val addressAutofillCoordinator = get<AddressAutofillCoordinator>()
            it.autocompleteStorageDelegate = AutocompleteStorageDelegate(
                addressRepository = get(),
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                onAddressFetched = addressAutofillCoordinator::onAddressFetch,
            )
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
    single { WebShareFilesWebExtension().also { it.install(get()) } }
    // 拡張機能のツールバーアクションはランタイム単位で受け取るため single
    single { WebExtensionActionController(get()) }
    single { ExtensionRuntimeCoordinator(get()) }
    // eTLD+1 (基底ドメイン) の算出に使用する Public Suffix List。初回ロードを共有するため single
    single { PublicSuffixList(androidContext()) }
    factory { GeckoDownloadManager(androidContext(), get()) }
    viewModel { BrowserViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    worker { DownloadWorker(get(), get(), get()) }
}

private const val ADDRESS_AUTOFILL_ENABLED_PREF = "extensions.formautofill.addresses.enabled"
private const val ADDRESS_AUTOFILL_CAPTURE_ENABLED_PREF = "extensions.formautofill.addresses.capture.enabled"
private const val ADDRESS_AUTOFILL_SUPPORTED_PREF = "extensions.formautofill.addresses.supported"

/** GeckoView に公開設定 API がない住所自動入力を内部プリファレンスで有効にする。 */
@OptIn(ExperimentalGeckoViewApi::class)
private fun enableAddressAutofill() {
    GeckoPreferenceController.setGeckoPrefs(
        listOf(
            GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                ADDRESS_AUTOFILL_ENABLED_PREF,
                true,
                GeckoPreferenceController.PREF_BRANCH_USER,
            ),
            GeckoPreferenceController.SetGeckoPreference.setBoolPref(
                ADDRESS_AUTOFILL_CAPTURE_ENABLED_PREF,
                true,
                GeckoPreferenceController.PREF_BRANCH_USER,
            ),
            GeckoPreferenceController.SetGeckoPreference.setStringPref(
                ADDRESS_AUTOFILL_SUPPORTED_PREF,
                "on",
                GeckoPreferenceController.PREF_BRANCH_USER,
            ),
        ),
    ).accept(
        { results ->
            val failed = results.orEmpty().filterValues { !it }.keys
            if (failed.isNotEmpty()) Log.w("AppModule", "住所自動入力プリファレンスの設定に失敗: $failed")
        },
        { error -> Log.w("AppModule", "住所自動入力プリファレンスの設定に失敗", error) },
    )
}

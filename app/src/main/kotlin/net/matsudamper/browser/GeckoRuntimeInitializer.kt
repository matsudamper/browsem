package net.matsudamper.browser

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.address.AddressRepository
import net.matsudamper.browser.data.resolvedExtensionsProcessEnabled
import net.matsudamper.browser.data.resolvedInputAutoZoomEnabled
import net.matsudamper.browser.data.resolvedWebAuthnPlatformAuthenticatorAvailableOverrideEnabled
import net.matsudamper.browser.feature.addressautofill.AddressAutofillCoordinator
import net.matsudamper.browser.feature.addressautofill.AddressAutofillWebExtension
import net.matsudamper.browser.feature.addressautofill.AutocompleteStorageDelegate
import net.matsudamper.browser.feature.forminputautofill.FormInputAutofillWebExtension
import net.matsudamper.browser.feature.webauthncompat.WebAuthnCompatWebExtension
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

private const val TAG = "GeckoRuntimeInitializer"

private const val ADDRESS_AUTOFILL_ENABLED_PREF = "extensions.formautofill.addresses.enabled"
private const val ADDRESS_AUTOFILL_CAPTURE_ENABLED_PREF = "extensions.formautofill.addresses.capture.enabled"
private const val ADDRESS_AUTOFILL_SUPPORTED_PREF = "extensions.formautofill.addresses.supported"

/**
 * GeckoRuntime の生成と、生成直後に必要な拡張機能インストール・delegate 設定をまとめて行う。
 *
 * DI の生成ラムダに置くと解決した任意のスレッド（カスタムタブの Binder スレッド等）で
 * GeckoRuntime が初期化され、設定値の読み出しもブロッキングになる。
 * 初期化はこのクラスに閉じ、呼び出し側は [initialize] を待ってから GeckoRuntime を使う。
 */
internal class GeckoRuntimeInitializer(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val addressRepository: AddressRepository,
    private val addressAutofillWebExtension: AddressAutofillWebExtension,
    private val formInputAutofillWebExtension: FormInputAutofillWebExtension,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    private val webAuthnCompatWebExtension: WebAuthnCompatWebExtension,
    private val addressAutofillCoordinator: AddressAutofillCoordinator,
    private val autocompleteCoroutineScope: CoroutineScope,
) {
    private val initializeMutex = Mutex()
    private val mutableRuntime = MutableStateFlow<GeckoRuntime?>(null)

    /** 初期化が完了すると GeckoRuntime が流れる。完了前は null。 */
    val runtime: StateFlow<GeckoRuntime?> = mutableRuntime.asStateFlow()

    val isInitialized: Boolean get() = mutableRuntime.value != null

    /** GeckoRuntime を初期化する。初期化済みなら生成済みのものを返す。 */
    suspend fun initialize(): GeckoRuntime {
        mutableRuntime.value?.let { return it }
        return initializeMutex.withLock {
            mutableRuntime.value ?: createRuntime(settingsRepository.settings.first())
        }
    }

    /**
     * 初期化済みの GeckoRuntime を同期的に取得する。
     * [initialize] を待ってから使う経路でのみ呼ぶこと。
     */
    fun requireInitialized(): GeckoRuntime {
        return checkNotNull(mutableRuntime.value) {
            "GeckoRuntime が未初期化。initialize() の完了を待ってから使うこと。"
        }
    }

    private suspend fun createRuntime(browserSettings: BrowserSettings): GeckoRuntime {
        return withContext(Dispatchers.Main.immediate) {
            // Gecko 起動前の pref 設定はキューされる。メインスレッドをブロックして待機すると
            // 起動処理とデッドロックするため非同期で投入する。
            enableAddressAutofill()
            val created = GeckoRuntime.create(
                context,
                GeckoRuntimeSettings.Builder()
                    .forceUserScalableEnabled(true)
                    .inputAutoZoomEnabled(browserSettings.resolvedInputAutoZoomEnabled())
                    .extensionsProcessEnabled(browserSettings.resolvedExtensionsProcessEnabled())
                    .build(),
            )
            addressAutofillWebExtension.install(created)
            formInputAutofillWebExtension.install(created)
            pageTranslationWebExtension.install(created)
            // MainActivity の install() はこの設定反映まで含んだ同じ GeckoResult を待つ。
            // retryInstall() も保存済みの有効状態を再適用する。
            webAuthnCompatWebExtension.setEnabled(
                created,
                browserSettings.resolvedWebAuthnPlatformAuthenticatorAvailableOverrideEnabled(),
            )
            created.autocompleteStorageDelegate = AutocompleteStorageDelegate(
                addressRepository = addressRepository,
                coroutineScope = autocompleteCoroutineScope,
                onAddressFetchStarted = addressAutofillCoordinator::onAddressFetchStarted,
                onAddressFetched = addressAutofillCoordinator::onAddressFetch,
            )
            // 署名要求は GeckoRuntimeSettings では設定できず pref でしか制御できない。
            // 起動時の検証にも使われる値のため runtime 生成のたびに反映する。
            // Gecko 起動前の呼び出しはキューされる。
            allowUnsignedExtensions().accept({}, { error ->
                Log.w(TAG, "署名要求 pref の反映に失敗", error)
            })
            mutableRuntime.value = created
            created
        }
    }
}

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
            if (failed.isNotEmpty()) Log.w(TAG, "住所自動入力プリファレンスの設定に失敗: $failed")
        },
        { error -> Log.w(TAG, "住所自動入力プリファレンスの設定に失敗", error) },
    )
}

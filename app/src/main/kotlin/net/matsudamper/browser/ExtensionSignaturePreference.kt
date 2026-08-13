package net.matsudamper.browser

import androidx.annotation.OptIn
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult

/**
 * 拡張機能の署名を必須とするかどうかの Gecko pref。
 *
 * GeckoView の公開 API には署名要求を切り替える設定がなく、この pref でのみ制御できる。
 * この GeckoView ビルドは MOZ_REQUIRE_SIGNING が無効なため、pref の値が
 * AddonSettings.REQUIRE_SIGNING に反映される。
 */
private const val PREF_XPINSTALL_SIGNATURES_REQUIRED = "xpinstall.signatures.required"

/**
 * 署名されていない拡張機能のインストールを許可する。
 *
 * 署名の要求は拡張機能ごとではなくブラウザ全体の設定で、インストール時だけでなく起動時の
 * 検証にも使われる。必須に戻すとインストール済みの署名なし拡張機能が無効化されるため、
 * GeckoRuntime の生成のたびに許可を反映する。
 */
@OptIn(markerClass = [ExperimentalGeckoViewApi::class])
internal fun allowUnsignedExtensions(): GeckoResult<Void> {
    return GeckoPreferenceController.setGeckoPref(
        PREF_XPINSTALL_SIGNATURES_REQUIRED,
        false,
        GeckoPreferenceController.PREF_BRANCH_USER,
    )
}

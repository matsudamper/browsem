package net.matsudamper.browser

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
 * 署名されていない拡張機能を許可するかどうかを Gecko に反映する。
 *
 * インストール時だけでなく起動時の検証にも使われる値で、必須に戻すとインストール済みの
 * 署名なし拡張機能は無効化される。そのため設定として永続化した値を毎回反映する。
 */
internal fun applyAllowUnsignedExtensions(allowUnsigned: Boolean): GeckoResult<Void> {
    return GeckoPreferenceController.setGeckoPref(
        PREF_XPINSTALL_SIGNATURES_REQUIRED,
        !allowUnsigned,
        GeckoPreferenceController.PREF_BRANCH_USER,
    )
}

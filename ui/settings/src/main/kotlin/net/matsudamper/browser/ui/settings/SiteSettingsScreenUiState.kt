package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState

@Stable
data class SiteSettingsScreenUiState(
    val callbacks: Callbacks,
    val host: String,
    /** マイク権限の状態。サイトから一度も要求されていない場合は null で、項目を表示しない */
    val microphonePermission: SitePermissionState?,
    /** 位置情報の扱い。サイトから一度も要求されていない場合は null で、項目を表示しない */
    val geolocationState: SiteGeolocationState?,
    /** TLS 証明書の表示情報。タブから取得できない場合は null で、項目を表示しない */
    val tlsCertificate: TlsCertificate?,
) {
    @Stable
    sealed interface TlsCertificate {
        /** HTTPS で保護されていない接続 */
        data object Insecure : TlsCertificate

        /** 保護された接続の証明書詳細 */
        data class Available(
            val subjectCommonName: String,
            val issuer: String,
            val validFrom: String,
            val validUntil: String,
            val sha256Fingerprint: String,
        ) : TlsCertificate
    }

    interface Callbacks {
        fun setMicrophonePermission(state: SitePermissionState)
        fun setGeolocationState(state: SiteGeolocationState)
    }
}

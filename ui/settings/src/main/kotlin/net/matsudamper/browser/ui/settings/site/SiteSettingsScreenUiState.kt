package net.matsudamper.browser.ui.settings.site

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
    /**
     * 音声付きメディアの自動再生の状態。
     * サイトから一度も要求されていない場合は null で、項目を表示しない
     */
    val autoplayPermission: SitePermissionState?,
    /** TLS 証明書の表示情報。タブから取得できない場合は null で、項目を表示しない */
    val tlsCertificate: TlsCertificate?,
    /** 削除確認ダイアログの対象。null の場合はダイアログを表示しない */
    val clearDataConfirmDialog: ClearDataType?,
    /** 削除完了スナックバーのメッセージ。表示後に consumeClearDataResultMessage で消費する */
    val clearDataResultMessage: String?,
    /** 保存されたフォーム入力の path 数。0 のときは項目を表示しない */
    val savedFormInputPathCount: Int,
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

    enum class ClearDataType {
        Cookie,
        Cache,
    }

    interface Callbacks {
        fun setMicrophonePermission(state: SitePermissionState)
        fun setGeolocationState(state: SiteGeolocationState)
        fun setAutoplayPermission(state: SitePermissionState)
        fun requestClearData(type: ClearDataType)
        fun confirmClearData()
        fun dismissClearDataConfirm()
        fun consumeClearDataResultMessage()
        fun openSavedFormInputs()
    }
}

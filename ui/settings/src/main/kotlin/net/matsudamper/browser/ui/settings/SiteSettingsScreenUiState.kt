package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.SitePermissionState

@Stable
data class SiteSettingsScreenUiState(
    val callbacks: Callbacks,
    val host: String,
    /** マイク権限の状態。サイトから一度も要求されていない場合は null で、項目を表示しない */
    val microphonePermission: SitePermissionState?,
    /** 削除確認ダイアログの対象。null の場合はダイアログを表示しない */
    val clearDataConfirmDialog: ClearDataType?,
    /** 削除完了スナックバーのメッセージ。表示後に consumeClearDataResultMessage で消費する */
    val clearDataResultMessage: String?,
) {
    enum class ClearDataType {
        Cookie,
        Cache,
    }

    interface Callbacks {
        fun setMicrophonePermission(state: SitePermissionState)
        fun requestClearData(type: ClearDataType)
        fun confirmClearData()
        fun dismissClearDataConfirm()
        fun consumeClearDataResultMessage()
    }
}

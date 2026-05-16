package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable

@Stable
data class BackupProgressUiState(
    val isImport: Boolean,
    val phase: Phase,
    val callbacks: Callbacks,
) {
    sealed interface Phase {
        /** ユーザーに操作確認を求めているフェーズ */
        data object Confirming : Phase

        /** バックアップ処理を実行中のフェーズ */
        data object InProgress : Phase

        /** 処理が正常に完了したフェーズ */
        data class Completed(val successMessage: String) : Phase

        /** エラーが発生したフェーズ */
        data class Error(val message: String, val pendingRestart: Boolean) : Phase

        /** インポート成功後、再起動を促すフェーズ */
        data class PendingRestart(val errorMessage: String?) : Phase
    }

    interface Callbacks {
        /** ダイアログの「開始」ボタンが押された */
        fun onConfirm()

        /** ダイアログの「キャンセル」ボタンが押された */
        fun onCancel()

        /** 画面を閉じる */
        fun onDismiss()

        /** アプリを再起動する */
        fun onRestart()
    }
}

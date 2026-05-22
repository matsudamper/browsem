package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable

@Stable
data class BackupProgressUiState(
    val isImport: Boolean,
    val phase: Phase,
    val callbacks: Callbacks,
) {
    sealed interface Phase {
        /** ファイルピッカー表示中（選択待ち） */
        data object WaitingForFile : Phase

        /** バックアップ処理を実行中のフェーズ。message には現在の処理内容を表示する */
        data class InProgress(val message: String) : Phase

        /** 処理が正常に完了したフェーズ */
        data class Completed(val successMessage: String) : Phase

        /** エラーが発生したフェーズ */
        data class Error(val message: String, val pendingRestart: Boolean) : Phase

        /** インポート成功後、再起動を促すフェーズ */
        data class PendingRestart(val errorMessage: String?) : Phase
    }

    interface Callbacks {
        /** 画面を閉じる */
        fun onDismiss()

        /** アプリを再起動する */
        fun onRestart()
    }
}

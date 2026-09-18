package net.matsudamper.browser.ui.settings.backup

import androidx.compose.runtime.Stable

@Stable
data class BackupProgressUiState(
    val isImport: Boolean,
    val phase: Phase,
    val callbacks: Callbacks,
) {
    sealed interface Phase {
        data object WaitingForFile : Phase

        data class InProgress(
            val message: String,
            val fraction: Float? = null,
        ) : Phase

        data class Completed(val successMessage: String) : Phase

        data class Error(val message: String, val pendingRestart: Boolean) : Phase

        /** インポート成功後、再起動を促すフェーズ */
        data class PendingRestart(val errorMessage: String?) : Phase
    }

    interface Callbacks {
        fun onDismiss()

        fun onRestart()
    }
}
